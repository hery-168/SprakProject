package com.erik.sparkproject.spark.session;

import java.text.ParseException;
import java.util.Date;
import java.util.Iterator;

import org.apache.spark.Accumulator;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.hive.HiveContext;

import com.alibaba.fastjson.JSONObject;
import com.erik.sparkproject.conf.ConfigurationManager;
import com.erik.sparkproject.constant.Constants;
import com.erik.sparkproject.dao.ITaskDAO;
import com.erik.sparkproject.domain.Task;
import com.erik.sparkproject.dao.factory.DAOFactory;
import com.erik.sparkproject.test.MockData;
import com.erik.sparkproject.util.*;

import scala.Tuple2;

/**
 * 这是spark本事提供的特性
 *
 * @author yongheng
 */
public class TestUserVisitSessionAnalyzeSpark05 {

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

        //聚合
        //首先，可以将行为数据按照session_id进行groupByKey分组
        //此时的数据粒度就是session粒度了，然后可以将session粒度的数据与用户信息数据惊醒join
        //然后就可以获取到session粒度的数据，同时数据里面还包含了session对应的user信息
        //到这里为止，获取的数据是<sessionid,(sessionid,searchKeywords,
        //clickCategoryIds,age,professional,city,sex)>
        JavaPairRDD<String, String> sessionid2AggrInfoRDD =
                aggregateBySession(sqlContext, actionRDD);


        //接着，就要针对session粒度的聚合数据，按照使用者指定的筛选参数进行数据过滤
        //相当于我们自己编写的算子，是要访问外面的任务参数对象的
        //匿名内部类（算子函数），访问外部对象，是要给外部对象使用final修饰的

        //重构，同时进行过滤和统计
        Accumulator<String> sessionAggrStatAccumulator = sc.accumulator(
                "", new SesssionAggrStatAccumulator());


        JavaPairRDD<String, String> filteredSessionid2AggrInfoRDD = filterSessionAndAggrStat(
                sessionid2AggrInfoRDD, taskParam, sessionAggrStatAccumulator);

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
                + "where date>='" + startDate + "'"
                + "and date<='" + endDate + "'";

        DataFrame actionDF = sqlContext.sql(sql);

        return actionDF.javaRDD();
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
        //到此为止，获取的数据格式如下：<userid,partAggrInfo(sessionid,searchKeywords,clickCategoryIds...)>
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
                        String searchKeywords = StringUtils.trimComma(searchKeywordsBuffer.toString());
                        String clickCategoryIds = StringUtils.trimComma(clickCategoryIdsBuffer.toString());

                        //计算session访问时长（秒）
                        long visitLength = (endTime.getTime() - startTime.getTime()) / 1000;

                        String partAggrInfo = Constants.FIELD_SESSION_ID + "=" + sessionid + "|"
                                + Constants.FIELD_SEARCH_KEYWORDS + "=" + searchKeywords + "|"
                                + Constants.FIELD_CLICK_CATEGORY_IDS + "=" + clickCategoryIds + "|"
                                + Constants.FIELD_VISIT_LENGTH + "=" + visitLength + "|"
                                + Constants.FIELD_STEP_LENGTH + "=" + stepLength;

                        return new Tuple2<Long, String>(userid, partAggrInfo);
                    }


                });

        //查询所有用户数据
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

                        //如果经过了之前的多个过滤条件之后，程序能够走到这里
                        //那么说明该session是通过了用户指定的筛选条件的，也就是需要保留的session
                        //那么就要对session的访问时长和访问步长进行统计，
                        //根据session对应的范围进行相应的累加计数
                        //只要走到这一步，那么就是需要计数的session
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

        return null;
    }

}
