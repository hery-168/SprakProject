package com.hyh.sparkproject.test;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * fastjson测试类
 * @author Erik
 *
 */
public class FastjsonTest {
	public static void main(String[] args) {
//		String json = "[{'学生':'张三','班级':'一班','年级':'大一','科目':'高数','成绩':90},"
//				+ "{'学生':'李四','班级':'二班','年级':'大一','科目':'高数','成绩':80}]";
		String json="{'startDate':'2018-02-27','endDate':'2018-02-28'}";
		json="{'startDate':'2018-02-27','endDate':'2018-02-28'}";
//		JSONArray jsonArray = JSONArray.parseArray(json);
//		JSONObject jsonObject = jsonArray.getJSONObject(0);
//		System.out.println(jsonObject.getString("startDate"));

		JSONObject taskParam = JSONObject.parseObject(json);
		System.out.println("aa:   "+taskParam);
//		JSONArray jsonArray = taskParam.getJSONArray("startDate");
//	String aa=	jsonArray.getString(0);
//		System.out.println("json"+taskParam);
//		System.out.println(aa);
	}

}
