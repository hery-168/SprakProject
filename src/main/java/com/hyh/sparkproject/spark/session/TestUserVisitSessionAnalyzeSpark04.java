package com.hyh.sparkproject.spark.session;
import java.util.Date;
import java.util.Iterator;

import com.hyh.sparkproject.test.MockData;
import com.hyh.sparkproject.util.DateUtils;
import com.hyh.sparkproject.util.ParamUtils;
import com.hyh.sparkproject.util.StringUtils;
import com.hyh.sparkproject.util.ValidUtils;
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
import com.hyh.sparkproject.conf.ConfigurationManager;
import com.hyh.sparkproject.constant.Constants;
import com.hyh.sparkproject.dao.ITaskDAO;
import com.hyh.sparkproject.domain.Task;
import com.hyh.sparkproject.dao.factory.DAOFactory;
import com.erik.sparkproject.util.*;

import scala.Tuple2;

/**
 * 用户访问session分析spark作业
 *
 * 接收用户创建的分析任务，用户可能指定的条件如下：
 * 1.时间范围：起始日期-结束日期
 * 2.性别：男或女
 * 3.年龄范围
 * 4.职业：多选
 * 5.城市：多选
 * 6.搜索词：多个搜索词，只要某个session中的任何一个
 *   action搜索过指定的关键词，那么session就符合条件
 * 7.点击品类：多个品类，只要某个session中的任何一个
 *   action点击过某个品类，那么session就符合条件
 *
 * 我们的Spark作业如何接受用户创建的任务呢？
 * J2EE平台在接收用户创建任务的请求之后，会将任务信息插入MySQL的task表中，
 * 任务参数以JSON格式封装在task_param字段中
 * 接着J2EE平台执行我们的spark-submit shell脚本，并将taskid作为参数传递给spark-submit shell脚本
 * spark-submit shell脚本，在执行时，是可以接收参数的，并且会将接收的参数传递给spark作业的main函数
 * 参数就封装在main函数得到args数组中
 *
 * 这是spark本事提供的特性
 *
 *
 * @author Erik
 *
 */
public class TestUserVisitSessionAnalyzeSpark04 {
    public static void main(String[] args) {
        args = new String[]{"2"};
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
        JavaPairRDD<String, String> filteredSessionid2AggrInfoRDD =
                filterSession(sessionid2AggrInfoRDD, taskParam);

        /**
         * session聚合统计（统计出访问时长和访问步长，各个区间的session数量占总session数量的比例）
         *
         * 如果不进行重构，直接来实现，思路：
         * 1、actionRDD，映射成<sessionid,Row>的格式
         * 2、按sessionid聚合，计算出每个session的访问时长和访问步长，生成一个新的RDD
         * 3、遍历新生成的RDD，将每个session的访问时长和访问步长，去更新自定义Accumulator中的对应的值
         * 4、使用自定义Accumulator中的统计值，去计算各个区间的比例
         * 5、将最后计算出来的结果，写入MySQL对应的表中
         *
         * 普通实现思路的问题：
         * 1、为什么还要用actionRDD，去映射？其实我们之前在session聚合的时候，映射已经做过了。多此一举
         * 2、是不是一定要，为了session的聚合这个功能，单独去遍历一遍session？其实没有必要，已经有session数据
         * 		之前过滤session的时候，其实，就相当于，是在遍历session，那么这里就没有必要再过滤一遍了
         *
         * 重构实现思路：
         * 1、不要去生成任何新的RDD（处理上亿的数据）
         * 2、不要去单独遍历一遍session的数据（处理上千万的数据）
         * 3、可以在进行session聚合的时候，就直接计算出来每个session的访问时长和访问步长
         * 4、在进行过滤的时候，本来就要遍历所有的聚合session信息，此时，就可以在某个session通过筛选条件后
         * 		将其访问时长和访问步长，累加到自定义的Accumulator上面去
         * 5、就是两种截然不同的思考方式，和实现方式，在面对上亿，上千万数据的时候，甚至可以节省时间长达
         * 		半个小时，或者数个小时
         *
         * 开发Spark大型复杂项目的一些经验准则：
         * 1、尽量少生成RDD
         * 2、尽量少对RDD进行算子操作，如果有可能，尽量在一个算子里面，实现多个需要做的功能
         * 3、尽量少对RDD进行shuffle算子操作，比如groupByKey、reduceByKey、sortByKey（map、mapToPair）
         * 		shuffle操作，会导致大量的磁盘读写，严重降低性能
         * 		有shuffle的算子，和没有shuffle的算子，甚至性能，会达到几十分钟，甚至数个小时的差别
         * 		有shfufle的算子，很容易导致数据倾斜，一旦数据倾斜，简直就是性能杀手（完整的解决方案）
         * 4、无论做什么功能，性能第一
         * 		在传统的J2EE或者.NET后者PHP，软件/系统/网站开发中，我认为是架构和可维护性，可扩展性的重要
         * 		程度，远远高于了性能，大量的分布式的架构，设计模式，代码的划分，类的划分（高并发网站除外）
         *
         * 		在大数据项目中，比如MapReduce、Hive、Spark、Storm，我认为性能的重要程度，远远大于一些代码
         * 		的规范，和设计模式，代码的划分，类的划分；大数据，大数据，最重要的，就是性能
         * 		主要就是因为大数据以及大数据项目的特点，决定了，大数据的程序和项目的速度，都比较慢
         * 		如果不优先考虑性能的话，会导致一个大数据处理程序运行时间长度数个小时，甚至数十个小时
         * 		此时，对于用户体验，简直就是一场灾难
         *
         * 		所以，推荐大数据项目，在开发和代码的架构中，优先考虑性能；其次考虑功能代码的划分、解耦合
         *
         * 		我们如果采用第一种实现方案，那么其实就是代码划分（解耦合、可维护）优先，设计优先
         * 		如果采用第二种方案，那么其实就是性能优先
         *
         * 		讲了这么多，其实大家不要以为我是在岔开话题，大家不要觉得项目的课程，就是单纯的项目本身以及
         * 		代码coding最重要，其实项目，我觉得，最重要的，除了技术本身和项目经验以外；非常重要的一点，就是
         * 		积累了，处理各种问题的经验
         *
         */

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
        //在my.properties中配置
        //spark.local=true（打包之前改为flase）
        //在ConfigurationManager.java中添加
        //public static Boolean getBoolean(String key) {
        //  String value = getProperty(key);
        //  try {
        //      return Boolean.valueOf(value);
        //  } catch (Exception e) {
        //      e.printStackTrace();
        //  }
        //  return false;
        //}
        //在Contants.java中添加
        //String SPARK_LOCAL = "spark.local";

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

        //先在Constants.java中添加任务相关的常量
        //String PARAM_START_DATE = "startDate";
        //String PARAM_END_DATE = "endDate";
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

                            //实际上这里要对数据说明一下
                            //并不是每一行访问行为都有searchKeyword和clickCategoryId两个字段的
                            //其实，只有搜索行为是有searchKeyword字段的
                            //只有点击品类的行为是有clickCaregoryId字段的
                            //所以，任何一行行为数据，都不可能两个字段都有，所以数据是可能出现null值的

                            //所以是否将搜索词点击品类id拼接到字符串中去
                            //首先要满足不能是null值
                            //其次，之前的字符串中还没有搜索词或者点击品类id

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

                        //计算session开始和结束时间
                        //现在DateUtils.java中添加方法
                        //public static Date parseTime(String time) {
                        //  try {
                        //      return TIME_FORMAT.parse(time);
                        //  } catch (ParseException e) {
                        //      e.printStackTrace();
                        //  }
                        //  return null;
                        //}

                        //StringUtils引入的包是import com.erik.sparkproject.util.trimComma;
                        String searchKeywords = StringUtils.trimComma(searchKeywordsBuffer.toString());
                        String clickCategoryIds = StringUtils.trimComma(clickCategoryIdsBuffer.toString());

                        //计算session访问时长（秒）
                        long visitLength = (endTime.getTime() - startTime.getTime()) / 1000;

                        //返回的数据即是<sessionid, partAggrInfo>
                        //但是，这一步聚合后，其实还需要将每一行数据，根对应的用户信息进行聚合
                        //问题来了，如果是跟用户信息进行聚合的话，那么key就不应该是sessionid，而应该是userid
                        //才能够跟<userid, Row>格式的用户信息进行聚合
                        //如果我们这里直接返回<sessionid, partAggrInfo>,还得再做一次mapToPair算子
                        //将RDD映射成<userid,partAggrInfo>的格式，那么就多此一举

                        //所以，我们这里其实可以直接返回数据格式就是<userid,partAggrInfo>
                        //然后在直接将返回的Tuple的key设置成sessionid
                        //最后的数据格式，还是<sessionid,fullAggrInfo>

                        //聚合数据，用什么样的格式进行拼接？
                        //我们这里统一定义，使用key=value|key=vale

                        //在Constants.java中定义spark作业相关的常量
                        //String FIELD_SESSION_ID = "sessionid";
                        //String FIELD_SEARCH_KEYWORDS = "searchKeywords";
                        //String FIELD_CLICK_CATEGORY_IDS = "clickCategoryIds";
                        //String FIELD_VISIT_LENGTH = "visitLength";
                        //String FIELD_STEP_LENGTH = "stepLength";
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

                        //在Constants.java中添加以下常量
                        //String FIELD_AGE = "age";
                        //String FIELD_PROFESSIONAL = "professional";
                        //String FIELD_CITY = "city";
                        //String FIELD_SEX = "sex";
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
     * 过滤session数据
     * @param sessionid2AggrInfoRDD
     * @return
     */
    private static JavaPairRDD<String, String> filterSession(
            JavaPairRDD<String, String> sessionid2AggrInfoRDD,
            final JSONObject taskParam) {
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

                        //接着，依次按照筛选条件进行过滤
                        //按照年龄范围进行过滤（startAge、endAge）
                        //先在Constants.java中添加常量
                        //String PARAM_START_AGE = "startAge";
                        //String PARAM_END_AGE = "endage";
                        //String PARAM_PROFESSIONALS = "professionals";
                        //String PARAM_CITIES = "cities";
                        //String PARAM_SEX = "sex";
                        //String PARAM_KEYWORDS = "keywords";
                        //String PARAM_CATEGORY_IDS = "categoryIds";
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
                        return true;
                    }
                });
        return null;
    }
}