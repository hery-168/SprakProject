package com.hyh.sparkproject.spark.session;

import com.alibaba.fastjson.JSONObject;
import com.hyh.sparkproject.conf.ConfigurationManager;
import com.hyh.sparkproject.constant.Constants;
import com.hyh.sparkproject.dao.ISessionAggrStatDAO;
import com.hyh.sparkproject.dao.ISessionDetailDAO;
import com.hyh.sparkproject.dao.ISessionRandomExtractDAO;
import com.hyh.sparkproject.dao.ITaskDAO;
import com.hyh.sparkproject.dao.factory.DAOFactory;
import com.hyh.sparkproject.domain.SessionAggrStat;
import com.hyh.sparkproject.domain.SessionDetail;
import com.hyh.sparkproject.domain.SessionRandomExtract;
import com.hyh.sparkproject.domain.Task;
import com.hyh.sparkproject.test.MockData;
import com.hyh.sparkproject.util.*;
import org.apache.spark.Accumulator;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.hive.HiveContext;
import scala.Tuple2;

import java.util.*;

/**
 * 用户访问session分析spark作业
 *
 * 增加 随机session  获取
 * @author Erik
 *
 */
public class TestUserVisitSessionAnalyzeSpark07 {

    public static void main(String[] args) {
        args = new String[]{"1"};
        //构建spark上下文

        //首先在Constants.java中设置spark作业相关的常量
        //String SPARK_APP_NAME = "UserVisitSessionAnalyzeSpark";
        //保存Constants.java配置
        SparkConf conf = new SparkConf()
                .setAppName(Constants.SPARK_APP_NAME)
                .setMaster("local");

        JavaSparkContext sc = new JavaSparkContext(conf);
        SQLContext sqlContext = getSQLContext(sc.sc());

        //生成模拟测试数据
        mockData(sc, sqlContext);

        //创建需要使用的DAO组件
        ITaskDAO taskDAO = DAOFactory.getTaskDAO();

        //那么就首先得查询出来指定的任务，并获取任务的查询参数
        long taskid = ParamUtils.getTaskIdFromArgs(args);
        Task task = taskDAO.findById(taskid);
        JSONObject taskParam = JSONObject.parseObject(task.getTaskParam());

        //如果要进行session粒度的数据聚合，
        //首先要从user_visit_action表中，查询出来指定日期范围内的数据
        JavaRDD<Row> actionRDD = getActionRDDByDateRange(sqlContext, taskParam);
        JavaPairRDD<String, Row> sessionid2actionRDD = getSessionid2ActionRDD(actionRDD);

        //聚合
        //首先，可以将行为数据按照session_id进行groupByKey分组
        //此时的数据粒度就是session粒度了，然后可以将session粒度的数据与用户信息数据惊醒join
        //然后就可以获取到session粒度的数据，同时数据里面还包含了session对应的user信息
        //到这里为止，获取的数据是<sessionid,(sessionid,searchKeywords,
        //clickCategoryIds,age,professional,city,sex)>
        JavaPairRDD<String, String> sessionid2AggrInfoRDD =
                aggregateBySession(sqlContext, actionRDD);

        //重构，同时进行过滤和统计
        Accumulator<String> sessionAggrStatAccumulator = sc.accumulator(
                "", new SesssionAggrStatAccumulator());


        JavaPairRDD<String, String> filteredSessionid2AggrInfoRDD = filterSessionAndAggrStat(
                sessionid2AggrInfoRDD, taskParam, sessionAggrStatAccumulator);

//        System.out.println(filteredSessionid2AggrInfoRDD.count());
       randomExtractSession(task.getTaskid(),filteredSessionid2AggrInfoRDD, sessionid2actionRDD);
        //计算出各个范围的session占比，并写入MySQL
        /**
         * 特别说明
         * 我们知道，要将上一个功能的session聚合统计数据获取到，就必须是在一个action操作触发job之后
         * 才能从Accumulator中获取数据，否则是获取不到数据的，因为没有job执行，Accumulator的值为空
         * 所以，我们在这里，将随机抽取的功能的实现代码，放在session聚合统计功能的最终计算和写库之前
         * 因为随机抽取功能中，有一个countByKey算子，是action操作，会触发job
         *
         * filteredSessionid2AggrInfoRDD.count() 通过这个来触发job
         */
        calculateAndPersistAggrStat(sessionAggrStatAccumulator.value(), task.getTaskid());

        //关闭spark上下文
        sc.close();

    }


    /**
     * 获取SQLContext
     * 如果在本地测试环境的话，那么久生成SQLC哦那text对象
     *如果在生产环境运行的话，那么就生成HiveContext对象
     * @param sc SparkContext
     * @return SQLContext
     */
    private static SQLContext getSQLContext(SparkContext sc) {

        boolean local = ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
        if(local) {
            return new SQLContext(sc);
        }else {
            return new HiveContext(sc);
        }
    }

    /**
     * 生成模拟数据
     * 只有是本地模式，才会生成模拟数据
     * @param sc
     * @param sqlContext
     */
    private static void mockData(JavaSparkContext sc, SQLContext sqlContext) {
        boolean local = ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
        if(local) {
            MockData.mock(sc, sqlContext);
        }
    }

    /**
     * 获取指定日期范围内的用户访问行为数据
     * @param sqlContext SQLContext
     * @param taskParam 任务参数
     * @return 行为数据RDD
     */
    private static JavaRDD<Row> getActionRDDByDateRange(
            SQLContext sqlContext, JSONObject taskParam) {

        String startDate = ParamUtils.getParam(taskParam, Constants.PARAM_START_DATE);
        String endDate = ParamUtils.getParam(taskParam, Constants.PARAM_END_DATE);

        String sql = "select * "
                + "from user_visit_action"
                + " where date >='" + startDate + "'"
                + " and date <='" + endDate + "'";

        DataFrame actionDF = sqlContext.sql(sql);

        return actionDF.javaRDD();
    }

    /**
     * 获取sessionid2到访问行为数据的映射的RDD
     * @param actionRDD
     * @return
     */
    public static JavaPairRDD<String, Row> getSessionid2ActionRDD(JavaRDD<Row> actionRDD) {
        return actionRDD.mapToPair(new PairFunction<Row, String, Row>(){
            private static final long serialVersionUID = 1L;

            public Tuple2<String, Row> call(Row row) throws Exception {
                return new Tuple2<String, Row>(row.getString(2), row);
            }
        });

    }
    /**
     * 对行为数据按sesssion粒度进行聚合
     * @param actionRDD 行为数据RDD
     * @return session粒度聚合数据
     */
    private static JavaPairRDD<String, String> aggregateBySession(
            SQLContext sqlContext, JavaRDD<Row> actionRDD) {
        //现在actionRDD中的元素是Row，一个Row就是一行用户访问行为记录，比如一次点击或者搜索
        //现在需要将这个Row映射成<sessionid,Row>的格式
        JavaPairRDD<String, Row> sessionid2ActionRDD = actionRDD.mapToPair(

                /**
                 * PairFunction
                 * 第一个参数，相当于是函数的输入
                 * 第二个参数和第三个参数，相当于是函数的输出（Tuple），分别是Tuple第一个和第二个值
                 */
                new PairFunction<Row, String, Row>() {

                    private static final long serialVersionUID = 1L;

                    public Tuple2<String, Row> call(Row row) throws Exception {

                        //按照MockData.java中字段顺序获取
                        //此时需要拿到session_id，序号是2
                        return new Tuple2<String, Row>(row.getString(2), row);
                    }

                });

        //对行为数据按照session粒度进行分组
        JavaPairRDD<String, Iterable<Row>> sessionid2ActionsRDD =
                sessionid2ActionRDD.groupByKey();

        //对每一个session分组进行聚合，将session中所有的搜索词和点击品类都聚合起来
        //到此为止，获取的数据格式如下：<userid,partAggrInfo(sessionid,searchKeywords,clickCategoryIds)>
        JavaPairRDD<Long, String> userid2PartAggrInfoRDD = sessionid2ActionsRDD.mapToPair(
                new PairFunction<Tuple2<String, Iterable<Row>>, Long, String>() {

                    private static final long serialVersionUID = 1L;

                    public Tuple2<Long, String> call(Tuple2<String, Iterable<Row>> tuple)
                            throws Exception {
                        String sessionid = tuple._1;
                        Iterator<Row> iterator = tuple._2.iterator();

                        StringBuffer searchKeywordsBuffer = new StringBuffer("");
                        StringBuffer clickCategoryIdsBuffer = new StringBuffer("");

                        Long userid = null;

                        //session的起始和结束时间
                        Date startTime = null;
                        Date endTime = null;
                        //session的访问步长
                        int stepLength = 0;

                        //遍历session所有的访问行为
                        while(iterator.hasNext()) {
                            //提取每个 访问行为的搜索词字段和点击品类字段
                            Row row = iterator.next();
                            if(userid == null) {
                                userid = row.getLong(1);
                            }
                            String searchKeyword = row.getString(5);
                            Long clickCategoryId = row.getLong(6);

                            if(StringUtils.isNotEmpty(searchKeyword)) {
                                if(!searchKeywordsBuffer.toString().contains(searchKeyword)) {
                                    searchKeywordsBuffer.append(searchKeyword + ",");
                                }
                            }
                            if(clickCategoryId != null) {
                                if(!clickCategoryIdsBuffer.toString().contains(
                                        String.valueOf(clickCategoryId))) {
                                    clickCategoryIdsBuffer.append(clickCategoryId + ",");
                                }
                            }

                            //计算session开始和结束时间
                            Date actionTime = DateUtils.parseTime(row.getString(4));
                            if(startTime == null) {
                                startTime = actionTime;
                            }
                            if(endTime == null) {
                                endTime = actionTime;
                            }

                            if(actionTime.before(startTime)) {
                                startTime = actionTime;
                            }
                            if(actionTime.after(endTime)) {
                                endTime = actionTime;
                            }

                            //计算session访问步长
                            stepLength ++;
                        }

                        //StringUtils引入的包是import com.erik.sparkproject.util.trimComma;
                        String searchKeywords = StringUtils.trimComma(searchKeywordsBuffer.toString());
                        String clickCategoryIds = StringUtils.trimComma(clickCategoryIdsBuffer.toString());

                        //计算session访问时长（秒）
                        long visitLength = (endTime.getTime() - startTime.getTime()) / 1000;

                        String partAggrInfo = Constants.FIELD_SESSION_ID + "=" + sessionid + "|"
                                + Constants.FIELD_SEARCH_KEYWORDS + "=" + searchKeywords + "|"
                                + Constants.FIELD_CLICK_CATEGORY_IDS + "=" + clickCategoryIds + "|"
                                + Constants.FIELD_VISIT_LENGTH + "=" + visitLength + "|"
                                + Constants.FIELD_STEP_LENGTH + "=" + stepLength+ "|"
                                + Constants.FIELD_START_TIME + "=" + DateUtils.formatTime(startTime);

                        return new Tuple2<Long, String>(userid, partAggrInfo);
                    }

                });

        //查询所有用户数据，并映射成<userid,Row>的格式
        String sql = "select * from user_info";
        JavaRDD<Row> userInfoRDD = sqlContext.sql(sql).javaRDD();

        JavaPairRDD<Long, Row> userid2InfoRDD = userInfoRDD.mapToPair(
                new PairFunction<Row, Long, Row>(){

                    private static final long serialVersionUID = 1L;

                    public Tuple2<Long, Row> call(Row row) throws Exception {
                        return new Tuple2<Long, Row>(row.getLong(0), row);
                    }

                });

        //将session粒度聚合数据，与用户信息进行join
        JavaPairRDD<Long, Tuple2<String, Row>> userid2FullInfoRDD =
                userid2PartAggrInfoRDD.join(userid2InfoRDD);

        //对join起来的数据进行拼接，并且返回<sessionid,fullAggrInfo>格式的数据
        JavaPairRDD<String, String> sessionid2FullAggrInfoRDD = userid2FullInfoRDD.mapToPair(

                new PairFunction<Tuple2<Long, Tuple2<String, Row>>, String, String>() {

                    private static final long serialVersionUID = 1L;

                    public Tuple2<String, String> call(
                            Tuple2<Long, Tuple2<String, Row>> tuple) throws Exception {
                        String partAggrInfo = tuple._2._1;
                        Row userInfoRow = tuple._2._2;

                        String sessionid = StringUtils.getFieldFromConcatString(
                                partAggrInfo, "\\|", Constants.FIELD_SESSION_ID);

                        int age = userInfoRow.getInt(3);
                        String professional = userInfoRow.getString(4);
                        String city = userInfoRow.getString(5);
                        String sex = userInfoRow.getString(6);

                        String fullAggrInfo = partAggrInfo + "|"
                                + Constants.FIELD_AGE + "=" + age + "|"
                                + Constants.FIELD_PROFESSIONAL + "=" + professional + "|"
                                + Constants.FIELD_CITY + "=" + city + "|"
                                + Constants.FIELD_SEX + "=" + sex ;
                        return new Tuple2<String, String>(sessionid, fullAggrInfo);
                    }


                });
        return sessionid2FullAggrInfoRDD;
    }

    /**
     * 过滤session数据，并进行聚合统计
     * @param sessionid2AggrInfoRDD
     * @return
     */
    private static JavaPairRDD<String, String> filterSessionAndAggrStat(
            JavaPairRDD<String, String> sessionid2AggrInfoRDD,
            final JSONObject taskParam,
            final Accumulator<String> sessionAggrAccumulator) {
        //为了使用后面的ValieUtils,所以，首先将所有的筛选参数拼接成一个连接串
        String startAge = ParamUtils.getParam(taskParam, Constants.PARAM_END_AGE);
        String endAge = ParamUtils.getParam(taskParam, Constants.PARAM_END_AGE);
        String professionals = ParamUtils.getParam(taskParam, Constants.PARAM_PROFESSIONALS);
        String cities = ParamUtils.getParam(taskParam, Constants.PARAM_CITIES);
        String sex = ParamUtils.getParam(taskParam, Constants.PARAM_SEX);
        String keywords = ParamUtils.getParam(taskParam, Constants.PARAM_KEYWORDS);
        String categoryIds = ParamUtils.getParam(taskParam, Constants.PARAM_CATEGORY_IDS);

        String _parameter = (startAge != null ? Constants.PARAM_START_AGE + "=" + startAge + "|" : "")
                + (endAge != null ? Constants.PARAM_END_AGE + "=" + endAge + "|" : "")
                + (professionals != null ? Constants.PARAM_PROFESSIONALS + "=" + professionals + "|" : "")
                + (cities != null ? Constants.PARAM_CITIES + "=" + cities + "|" : "")
                + (sex != null ? Constants.PARAM_SEX + "=" + sex + "|" : "")
                + (keywords != null ? Constants.PARAM_KEYWORDS + "=" + keywords + "|" : "")
                + (categoryIds != null ? Constants.PARAM_CATEGORY_IDS + "=" + categoryIds : "");

        if (_parameter.endsWith("\\|")) {
            _parameter = _parameter.substring(0, _parameter.length() - 1);
        }

        final String parameter = _parameter;

        //根据筛选参数进行过滤
        JavaPairRDD<String, String> filteredSessionid2AggrInfoRDD = sessionid2AggrInfoRDD.filter(

                new Function<Tuple2<String, String>, Boolean>() {


                    private static final long serialVersionUID = 1L;

                    public Boolean call(Tuple2<String, String> tuple) throws Exception {
                        //首先，从tuple中，获取聚合数据
                        String aggrInfo = tuple._2;

                        if(!ValidUtils.between(aggrInfo, Constants.FIELD_AGE,
                                parameter, Constants.PARAM_START_AGE, Constants.PARAM_END_AGE)) {
                            return false;
                        }

                        //按照职业范围进行过滤（professionals）
                        if(!ValidUtils.in(aggrInfo, Constants.FIELD_PROFESSIONAL,
                                parameter, Constants.PARAM_PROFESSIONALS)) {
                            return false;
                        }

                        //按照城市范围进行过滤（cities）
                        if(!ValidUtils.in(aggrInfo, Constants.FIELD_CITY,
                                parameter, Constants.PARAM_CATEGORY_IDS)) {
                            return false;
                        }

                        //按照性别过滤
                        if(!ValidUtils.equal(aggrInfo, Constants.FIELD_SEX,
                                parameter, Constants.PARAM_SEX)) {
                            return false;
                        }

                        //按照搜索词过滤
                        if(!ValidUtils.in(aggrInfo, Constants.FIELD_SEARCH_KEYWORDS,
                                parameter, Constants.PARAM_KEYWORDS)) {
                            return false;

                        }

                        //按照点击品类id进行搜索
                        if(!ValidUtils.in(aggrInfo, Constants.FIELD_CLICK_CATEGORY_IDS,
                                parameter, Constants.PARAM_CATEGORY_IDS)) {
                            return false;
                        }

                        sessionAggrAccumulator.add(Constants.SESSION_COUNT);

                        //计算出session的访问时长和访问步长的范围，并进行相应的累加

                        long visitLength = Long.valueOf(StringUtils.getFieldFromConcatString(
                                aggrInfo, "\\|", Constants.FIELD_VISIT_LENGTH));
                        long stepLength = Long.valueOf(StringUtils.getFieldFromConcatString(
                                aggrInfo, "\\|", Constants.FIELD_STEP_LENGTH));
                        calculateVisitLength(visitLength);
                        calculateStepLength(stepLength);

                        return true;
                    }

                    /**
                     * 计算访问时长范围
                     * @param visitLength
                     */
                    private void calculateVisitLength(long visitLength) {
                        if(visitLength >= 1 && visitLength <= 3) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_1s_3s);
                        }else if(visitLength >= 4 && visitLength <= 6) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_4s_6s);
                        }else if(visitLength >= 7 && visitLength <= 9) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_7s_9s);
                        }else if(visitLength >= 10 && visitLength <= 30) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_10s_30s);
                        }else if(visitLength > 30 && visitLength <= 60) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_30s_60s);
                        }else if(visitLength > 60 && visitLength <= 180) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_1m_3m);
                        }else if(visitLength > 180 && visitLength <= 600) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_3m_10m);
                        }else if(visitLength > 600 && visitLength <= 1800) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_10m_30m);
                        }else if(visitLength > 1800) {
                            sessionAggrAccumulator.add(Constants.TIME_PERIOD_30m);
                        }
                    }

                    /**
                     * 计算访问步长范围
                     * @param stepLength
                     */
                    private void calculateStepLength(long stepLength) {
                        if(stepLength >= 1 && stepLength <= 3) {
                            sessionAggrAccumulator.add(Constants.STEP_PERIOD_1_3);
                        }else if(stepLength >= 4 && stepLength <= 6) {
                            sessionAggrAccumulator.add(Constants.STEP_PERIOD_4_6);
                        }else if(stepLength >= 7 && stepLength <= 9) {
                            sessionAggrAccumulator.add(Constants.STEP_PERIOD_7_9);
                        }else if(stepLength >= 10 && stepLength <= 30) {
                            sessionAggrAccumulator.add(Constants.STEP_PERIOD_10_30);
                        }else if(stepLength > 30 && stepLength <= 60) {
                            sessionAggrAccumulator.add(Constants.STEP_PERIOD_30_60);
                        }else if(stepLength > 60) {
                            sessionAggrAccumulator.add(Constants.STEP_PERIOD_60);
                        }
                    }

                });

        return filteredSessionid2AggrInfoRDD;
    }

    /**
     * 随机抽取session
     *
     */
    private static void randomExtractSession(
            final long taskid,
            JavaPairRDD<String, String> sessionid2AggrInfoRDD,
            JavaPairRDD<String, Row> sessionid2actionRDD) {
        /**
         * 第一步，计算每天每小时的session数量
         */

        //获取<yyyy-mm-dd_hh,aggrinfo>格式的RDD
        JavaPairRDD<String, String> time2sessionidRDD = sessionid2AggrInfoRDD.mapToPair(
                new PairFunction<Tuple2<String, String>, String, String>(){

                    private static final long serialVersionUID = 1L;

                    public Tuple2<String, String> call(
                            Tuple2<String, String> tuple) throws Exception {
                        String aggrInfo = tuple._2;

                        String startTime = StringUtils.getFieldFromConcatString(
                                aggrInfo, "\\|", Constants.FIELD_START_TIME);
                        String dateHour = DateUtils.getDateHour(startTime);
                        return new Tuple2<String, String>(dateHour, aggrInfo);
                    }
                });

        //得到每天每小时的session数量
        Map<String, Object> countMap = time2sessionidRDD.countByKey();

        /**
         * 第二步，使用按是按比例随机抽取算法，计算出每小时要抽取session的索引
         */

        //将<yyyy-mm-dd_hh,count>格式的map转换成<yyyy-mm-dd,<hh,count>>的格式，方便后面使用
        Map<String, Map<String, Long>> dateHourCountMap =
                new HashMap<String, Map<String, Long>>();
        for(Map.Entry<String, Object>countEntry : countMap.entrySet()) {
            String dateHour = countEntry.getKey();
            String date = dateHour.split("_")[0];
            String hour = dateHour.split("_")[1];

            long count = Long.valueOf(String.valueOf(countEntry.getValue()));

            Map<String, Long> hourCountMap = dateHourCountMap.get(date);
            if(hourCountMap == null) {
                hourCountMap = new HashMap<String, Long>();
                dateHourCountMap.put(date, hourCountMap);
            }

            hourCountMap.put(hour, count);
        }

        //开始实现按照时间比例随机抽取算法

        //总共抽取100个session，先按照天数平分
        int extractNumberPerDay = 100 / dateHourCountMap.size();

        //每一天每一个小时抽取session的索引，<date,<hour,(3,5,20,200)>>
        final Map<String, Map<String, List<Integer>>> dateHourExtractMap =
                new HashMap<String, Map<String, List<Integer>>>();
        Random random = new Random();

        for(Map.Entry<String, Map<String, Long>> dateHourCountEntry : dateHourCountMap.entrySet()) {
            String date = dateHourCountEntry.getKey();
            Map<String, Long> hourCountMap = dateHourCountEntry.getValue();

            //计算出这一天的session总数
            long sessionCount = 0L;
            for(long hourCount : hourCountMap.values()) {
                sessionCount += hourCount;
            }

            Map<String, List<Integer>> hourExtractMap = dateHourExtractMap.get(date);
            if(hourExtractMap == null) {
                hourExtractMap = new HashMap<String, List<Integer>>();
                dateHourExtractMap.put(date, hourExtractMap);
            }

            //遍历每个小时
            for(Map.Entry<String, Long>hourCountEntry : hourCountMap.entrySet()) {
                String hour = hourCountEntry.getKey();
                long count = hourCountEntry.getValue();

                //计算每个小时的session数量，占据当天总session数量的比例，直接乘以每天要抽取的数量
                //就可以算出当前小时所需抽取的session数量
                long hourExtractNumber = (int)(((double)count / (double)sessionCount)
                        * extractNumberPerDay);

                if(hourExtractNumber > count) {
                    hourExtractNumber = (int)count;
                }

                //先获取当前小时的存放随机数list
                List<Integer> extractIndexList = hourExtractMap.get(hour);
                if(extractIndexList == null) {
                    extractIndexList = new ArrayList<Integer>();
                    hourExtractMap.put(hour, extractIndexList);
                }

                //生成上面计算出来的数量的随机数
                for(int i = 0; i < hourExtractNumber; i++) {
                    int extractIndex = random.nextInt((int)count);

                    //生成不重复的索引
                    while(extractIndexList.contains(extractIndex)) {
                        extractIndex = random.nextInt((int)count);
                    }

                    extractIndexList.add(extractIndex);
                }

            }

        }

        /**
         * 第三步：遍历每天每小时的session，根据随机索引抽取
         */

        //执行groupByKey算子，得到<dateHour,(session aggrInfo)>
        JavaPairRDD<String, Iterable<String>> time2sessionsRDD = time2sessionidRDD.groupByKey();

        //我们用flatMap算子遍历所有的<dateHour,(session aggrInfo)>格式的数据
        //然后会遍历每天每小时的session
        //如果发现某个session恰巧在我们指定的这天这小时的随机抽取索引上
        //那么抽取该session，直接写入MySQL的random_extract_session表
        //将抽取出来的session id返回回来，形成一个新的JavaRDD<String>
        //然后最后一步，用抽取出来的sessionid去join它们的访问行为明细数据写入session表
        JavaPairRDD<String, String> extractSessionidsRDD = time2sessionsRDD.flatMapToPair(

                new PairFlatMapFunction<Tuple2<String, Iterable<String>>, String, String>() {

                    private static final long serialVersionUID = 1L;

                    public Iterable<Tuple2<String, String>> call(
                            Tuple2<String, Iterable<String>> tuple)
                            throws Exception {
                        List<Tuple2<String, String>> extractSessionids =
                                new ArrayList<Tuple2<String, String>>();

                        String dateHour = tuple._1;
                        String date = dateHour.split("_")[0];
                        String hour = dateHour.split("_")[1];
                        Iterator<String> iterator = tuple._2.iterator();

                        //拿到这一天这一小时的随机索引
                        List<Integer> extractIndexList = dateHourExtractMap.get(date).get(hour);

                        ISessionRandomExtractDAO sessionRandomExtractDAO =
                                DAOFactory.getSessionRandomExtractDAO();

                        int index = 0;
                        while(iterator.hasNext()) {
                            String sessionAggrInfo = iterator.next();

                            if(extractIndexList.contains(index)) {
                                String sessionid = StringUtils.getFieldFromConcatString(
                                        sessionAggrInfo, "\\|", Constants.FIELD_SESSION_ID);

                                //将数据写入MySQL
                                SessionRandomExtract sessionRandomExtract = new SessionRandomExtract();

                                //增加参数
                                //private static void randomExtractSession(
                                //final long taskid,
                                //JavaPairRDD<String, String> sessionid2AggrInfoRDD)
                                sessionRandomExtract.setTaskid(taskid);
                                sessionRandomExtract.setSessionid(sessionid);
                                sessionRandomExtract.setStartTime(StringUtils.getFieldFromConcatString(
                                        sessionAggrInfo, "\\|", Constants.FIELD_START_TIME));
                                sessionRandomExtract.setSearchKeywords(StringUtils.getFieldFromConcatString(
                                        sessionAggrInfo, "\\|", Constants.FIELD_SEARCH_KEYWORDS));
                                sessionRandomExtract.setClickCategoryIds(StringUtils.getFieldFromConcatString(
                                        sessionAggrInfo, "\\|", Constants.FIELD_CLICK_CATEGORY_IDS));

                                sessionRandomExtractDAO.insert(sessionRandomExtract);

                                //将sessionid加入list
                                extractSessionids.add(new Tuple2<String, String>(sessionid, sessionid));
                            }

                            index ++;

                        }

                        return extractSessionids;
                    }

                });

        /**
         * 第四步：获取抽取出来的session的明细数据
         */
        JavaPairRDD<String, Tuple2<String, Row>> extractSessionDetailRDD =
                extractSessionidsRDD.join(sessionid2actionRDD);
        extractSessionDetailRDD.foreach(new VoidFunction<Tuple2<String, Tuple2<String, Row>>>() {


            private static final long serialVersionUID = 1L;

            public void call(Tuple2<String, Tuple2<String, Row>> tuple) throws Exception {
                //在包com.erik.sparkproject.domain中新建SessionDetail.java
                //在包com.erik.sparkproject.dao中新建ISessionDetailDAO.java接口
                //在包com.erik.sparkproject.impl中新建SessionDetailDAOImpl.java
                //在DAOFactory.java中添加
                //public static ISessionDetailDAO getSessionDetailDAO() {
                //return new SessionDetailDAOImpl();
                //}
                Row row = tuple._2._2;

                //封装sessionDetail的domain
                SessionDetail sessionDetail = new SessionDetail();
                sessionDetail.setTaskid(taskid);
                sessionDetail.setUserid(row.getLong(1));
                sessionDetail.setSessionid(row.getString(2));
                sessionDetail.setPageid(row.getLong(3));
                sessionDetail.setActionTime(row.getString(4));
                sessionDetail.setSearchKeyword(row.getString(5));
                sessionDetail.setClickCategoryId(row.getLong(6));
                sessionDetail.setClickProductId(row.getLong(7));
                sessionDetail.setOrderCategoryIds(row.getString(8));
                sessionDetail.setOrderProductIds(row.getString(9));
                sessionDetail.setPayCategoryIds(row.getString(10));
                sessionDetail.setPayProductIds(row.getString(11));

                ISessionDetailDAO sessionDetailDAO = DAOFactory.getSessionDetailDAO();
                sessionDetailDAO.insert(sessionDetail);
            }

        });
    }

    /*
     * 计算各session范围占比，并写入MySQL
     */
    private static void calculateAndPersistAggrStat(String value, long taskid) {
        //从Accumulator统计串中获取值
        long session_count = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.SESSION_COUNT));

        long visit_length_1s_3s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_1s_3s));
        long visit_length_4s_6s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_4s_6s));
        long visit_length_7s_9s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_7s_9s));
        long visit_length_10s_30s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_10s_30s));
        long visit_length_30s_60s = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_30s_60s));
        long visit_length_1m_3m = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_1m_3m));
        long visit_length_3m_10m = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_3m_10m));
        long visit_length_10m_30m = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_10m_30m));
        long visit_length_30m = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.TIME_PERIOD_30m));

        long step_length_1_3 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_1_3));
        long step_length_4_6 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_4_6));
        long step_length_7_9 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_7_9));
        long step_length_10_30 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_10_30));
        long step_length_30_60 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_30_60));
        long step_length_60 = Long.valueOf(StringUtils.getFieldFromConcatString(
                value, "\\|", Constants.STEP_PERIOD_60));

        //计算各个访问时长和访问步长的范围
        double visit_length_1s_3s_ratio = NumberUtils.formatDouble(
                (double)visit_length_1s_3s / (double)session_count, 2);
        double visit_length_4s_6s_ratio = NumberUtils.formatDouble(
                (double)visit_length_4s_6s / (double)session_count, 2);
        double visit_length_7s_9s_ratio = NumberUtils.formatDouble(
                (double)visit_length_7s_9s / (double)session_count, 2);
        double visit_length_10s_30s_ratio = NumberUtils.formatDouble(
                (double)visit_length_10s_30s / (double)session_count, 2);
        double visit_length_30s_60s_ratio = NumberUtils.formatDouble(
                (double)visit_length_30s_60s / (double)session_count, 2);
        double visit_length_1m_3m_ratio = NumberUtils.formatDouble(
                (double)visit_length_1m_3m / (double)session_count, 2);
        double visit_length_3m_10m_ratio = NumberUtils.formatDouble(
                (double)visit_length_3m_10m / (double)session_count, 2);
        double visit_length_10m_30m_ratio = NumberUtils.formatDouble(
                (double)visit_length_10m_30m / (double)session_count, 2);
        double visit_length_30m_ratio = NumberUtils.formatDouble(
                (double)visit_length_30m / (double)session_count, 2);

        double step_length_1_3_ratio = NumberUtils.formatDouble(
                (double)step_length_1_3 / (double)session_count, 2);
        double step_length_4_6_ratio = NumberUtils.formatDouble(
                (double)step_length_4_6 / (double)session_count, 2);
        double step_length_7_9_ratio = NumberUtils.formatDouble(
                (double)step_length_7_9 / (double)session_count, 2);
        double step_length_10_30_ratio = NumberUtils.formatDouble(
                (double)step_length_10_30 / (double)session_count, 2);
        double step_length_30_60_ratio = NumberUtils.formatDouble(
                (double)step_length_30_60 / (double)session_count, 2);
        double step_length_60_ratio = NumberUtils.formatDouble(
                (double)step_length_60 / (double)session_count, 2);

        //将访问结果封装成Domain对象
        SessionAggrStat sessionAggrStat = new SessionAggrStat();
        sessionAggrStat.setTaskid(taskid);
        sessionAggrStat.setSession_count(session_count);
        sessionAggrStat.setVisit_length_1s_3s_ratio(visit_length_1s_3s_ratio);
        sessionAggrStat.setVisit_length_4s_6s_ratio(visit_length_4s_6s_ratio);
        sessionAggrStat.setVisit_length_7s_9s_ratio(visit_length_7s_9s_ratio);
        sessionAggrStat.setVisit_length_10s_30s_ratio(visit_length_10s_30s_ratio);
        sessionAggrStat.setVisit_length_30s_60s_ratio(visit_length_30s_60s_ratio);
        sessionAggrStat.setVisit_length_1m_3m_ratio(visit_length_1m_3m_ratio);
        sessionAggrStat.setVisit_length_3m_10m_ratio(visit_length_3m_10m_ratio);
        sessionAggrStat.setVisit_length_10m_30m_ratio(visit_length_10m_30m_ratio);
        sessionAggrStat.setVisit_length_30m_ratio(visit_length_30m_ratio);

        sessionAggrStat.setStep_length_1_3_ratio(step_length_1_3_ratio);
        sessionAggrStat.setStep_length_4_6_ratio(step_length_4_6_ratio);
        sessionAggrStat.setStep_length_7_9_ratio(step_length_7_9_ratio);
        sessionAggrStat.setStep_length_10_30_ratio(step_length_10_30_ratio);
        sessionAggrStat.setStep_length_30_60_ratio(step_length_30_60_ratio);
        sessionAggrStat.setStep_length_60_ratio(step_length_60_ratio);

        //调用对用的DAO插入统计结果
        ISessionAggrStatDAO sessionAggrStatDAO = DAOFactory.getSessionAggrStatDAO();
        sessionAggrStatDAO.insert(sessionAggrStat);
    }

}