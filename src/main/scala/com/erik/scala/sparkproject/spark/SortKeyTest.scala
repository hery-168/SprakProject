package com.erik.scala.sparkproject.spark

import org.apache.spark.{SparkConf, SparkContext}

/**
  * Created by Administrator on 2018/3/5.
  */
object SortKeyTest {

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setAppName("SortKeyTest")
      .setMaster("local")
    val sc = new SparkContext(conf)

    val arr = Array(Tuple2(new SortKey(30, 35, 40), "1"),
      Tuple2(new SortKey(35, 30, 40), "2"),
      Tuple2(new SortKey(30, 38, 30), "3"))
    val rdd = sc.parallelize(arr, 1)

    val sortedRdd = rdd.sortByKey(false)

    for(tuple <- sortedRdd.collect()) {
      println(tuple._2)
    }
  }

}
