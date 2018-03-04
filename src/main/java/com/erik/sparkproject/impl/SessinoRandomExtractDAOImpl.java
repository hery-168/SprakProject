package com.erik.sparkproject.impl;

import com.erik.sparkproject.dao.ISessionRandomExtractDAO;
import com.erik.sparkproject.domain.SessionRandomExtract;
import com.erik.sparkproject.jdbc.JDBCHelper;

/**
 * 随机抽取session的DAO实现
 * @author Erik
 *
 */
public class SessinoRandomExtractDAOImpl implements ISessionRandomExtractDAO {

	//插入session随机抽取
	public void insert(SessionRandomExtract sessionRandomExtract) {
		String sql = "insert into session_random_extract values(?,?,?,?,?)";
		
		Object[] params = new Object[]{sessionRandomExtract.getTaskid(),
				sessionRandomExtract.getSessionid(),
				sessionRandomExtract.getStartTime(),
				sessionRandomExtract.getSearchKeywords(),
				sessionRandomExtract.getClickCategoryIds()};
		
		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeUpdate(sql, params);
	}

	public static void main(String[] args) {
		SessinoRandomExtractDAOImpl sss=new SessinoRandomExtractDAOImpl();
		SessionRandomExtract bean=new SessionRandomExtract();
		bean.setSessionid("111");
		bean.setClickCategoryIds("222");
		bean.setSearchKeywords("只能尴尬发大发打发");
		bean.setTaskid(2);
		bean.setStartTime("2017-7-7");
		sss.insert(bean);
	}
}
