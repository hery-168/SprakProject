package com.erik.sparkproject.spark.session;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.hive.HiveContext;

import com.erik.sparkproject.conf.ConfigurationManager;
import com.erik.sparkproject.constant.Constants;
import com.erik.sparkproject.test.MockData;

/**
 * 用户访问session分析spark作业
 * @author Erik
 *
 */
public class TestUserVisitSessionAnalyzeSpark01 {

    public static void main(String[] args) {
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

        //关闭spark上下文
        sc.close();
    }
    /**
     * 获取SQLContext
     * 如果在本地测试环境的话，那么久生成SQLContext对象
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
}
