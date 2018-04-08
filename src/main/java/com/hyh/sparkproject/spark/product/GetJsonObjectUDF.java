package com.hyh.sparkproject.spark.product;

import com.alibaba.fastjson.JSONObject;
import org.apache.spark.sql.api.java.UDF2;


/**
 * Created by Administrator on 2018/4/8.
 * 技术点：自定义UDF函数
 */
public class GetJsonObjectUDF implements UDF2<String, String, String> {
    @Override
    public String call(String json, String filed) throws Exception {

        try {
            JSONObject jsonObject = JSONObject.parseObject(json);
            return jsonObject.getString(filed);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
