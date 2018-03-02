package com.erik.sparkproject.spark.session;
import java.util.Iterator;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
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
 * Created by Administrator on 2018/2/28.
 */
public class TestUserVisitSessionAnalyzeSpark02 {
    public static void main(String[] args) {
        args= new String[] {"1"};
        //构建spark上下文

        //首先在Constants.java中设置spark作业相关的常量
        //String SPARK_APP_NAME = "UserVisitSessionAnalyzeSpark_finish";
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
        System.out.printf("TaskParam:   "+task.getTaskParam());
        String json="{'startDate':[{0:'2018-02-27'}],'endDate':[{0:'2018-02-28'}]}";
        json="{'startDate':'2018-02-27','endDate':'2018-02-28'}";
//        JSONObject taskParam = JSONObject.parseObject(task.getTaskParam());
        JSONObject taskParam = JSONObject.parseObject(json);
        //如果要进行session粒度的数据聚合，
        //首先要从user_visit_action表中，查询出来指定日期范围内的数据
        JavaRDD<Row> actionRDD = getActionRDDByDateRange(sqlContext, taskParam);
//        actionRDD.count();
        //聚合
        //首先，可以将行为数据按照session_id进行groupByKey分组
        //此时的数据粒度就是session粒度了，然后可以将session粒度的数据与用户信息数据惊醒join
        //然后就可以获取到session粒度的数据，同时数据里面还包含了session对应的user信息
        JavaPairRDD<String, String> sessionid2AggrInfoRDD =
                aggregateBySession(sqlContext, actionRDD);

        //关闭spark上下文
        sc.close();

    }

    /**
     * 获取SQLContext
     * 如果在本地测试环境的话，那么就生成SQLContext对象
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
        String startDate = ParamUtils.getParamExtend(taskParam, Constants.PARAM_START_DATE);
        String endDate = ParamUtils.getParamExtend(taskParam, Constants.PARAM_END_DATE);

        String sql = "select * "
                + " from user_visit_action"
                + " where date>= '" + startDate + "'"
                + " and date<= '" + endDate + "'";

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

                        //遍历session所有的访问行为
                        while(iterator.hasNext()) {
                            //提取每个 访问行为的搜索词字段和点击品类字段
                            Row row = iterator.next();
                            if(userid == null) {
                                userid = row.getLong(1);
                            }
                            String searchKeyword = row.getString(5);
                            long clickCategoryId = row.getLong(6);

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
                            if(!clickCategoryIdsBuffer.toString().contains(
                                    String.valueOf(clickCategoryId))) {
                                clickCategoryIdsBuffer.append(clickCategoryId + ",");
                            }
                        }

                        //StringUtils引入的包是import com.erik.sparkproject.util.trimComma;
//                        去除两边的逗号
                        String searchKeywords = StringUtils.trimComma(searchKeywordsBuffer.toString());
                        String clickCategoryIds = StringUtils.trimComma(clickCategoryIdsBuffer.toString());

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
                        String partAggrInfo = Constants.FIELD_SESSION_ID + "=" + sessionid + "|"
                                + Constants.FIELD_SEARCH_KEYWORDS + "=" + searchKeywords + "|"
                                + Constants.FIELD_CLICK_CATEGORY_IDS + "=" + clickCategoryIds;

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
//         JavaPairRDD<Long, Tuple2<String, Row>> Long 类型代表join 的key,也就是userid,
//        Tuple2<String, Row> 中的String 类型代表userid2PartAggrInfoRDD 这里面的PartAggrInfoRDD，
//        Row类型代表userid2InfoRDD 这里面的InfoRDD
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

//                        获取信息
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
}
