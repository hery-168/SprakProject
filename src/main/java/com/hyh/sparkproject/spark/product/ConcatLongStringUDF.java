package com.hyh.sparkproject.spark.product;

import org.apache.spark.sql.api.java.UDF3;

/**将两个字段拼接起来 使用指定的分隔符
 * Created by Administrator on 2018/4/8.
 * UDF3<Long,String,String,String>
 *     UDF3代表有3个输入参数，spark中支持有22个参数，也就是UDF22
 *     Long,String,String,String 分别代表3个输入参数的类型和最后返回值的类型
 */
public class ConcatLongStringUDF implements UDF3<Long,String,String,String>{
    @Override
    public String call(Long v1, String v2, String split) throws Exception {
        return String.valueOf(v1)+split+v2;
    }
}
