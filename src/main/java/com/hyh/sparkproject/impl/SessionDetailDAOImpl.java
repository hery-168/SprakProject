package com.hyh.sparkproject.impl;

import com.hyh.sparkproject.dao.ISessionDetailDAO;
import com.hyh.sparkproject.domain.SessionDetail;
import com.hyh.sparkproject.jdbc.JDBCHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * session明细DAO实现类
 * @author Erik
 *
 */
public class SessionDetailDAOImpl implements ISessionDetailDAO {

	public void insert(SessionDetail sessionDetail) {
		String sql = "insert into session_detail value(?,?,?,?,?,?,?,?,?,?,?,?)";
		Object[] params = new Object[] {
				sessionDetail.getTaskid(),
				sessionDetail.getUserid(),
				sessionDetail.getSessionid(),
				sessionDetail.getPageid(),
				sessionDetail.getActionTime(),
				sessionDetail.getSearchKeyword(),
				sessionDetail.getClickCategoryId(),
				sessionDetail.getClickProductId(),
				sessionDetail.getOrderCategoryIds(),
				sessionDetail.getOrderProductIds(),
				sessionDetail.getPayCategoryIds(),
				sessionDetail.getPayProductIds()};
		
		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeUpdate(sql, params);
		
	}

	/**
	 * 批量插入session明细数据
	 * @param sessionDetails
	 */
	public void insertBatch(List<SessionDetail> sessionDetails) {
		String sql = "insert into session_detail values(?,?,?,?,?,?,?,?,?,?,?,?)";

		List<Object[]> paramsList = new ArrayList<Object[]>();
		for(SessionDetail sessionDetail : sessionDetails) {
			Object[] params = new Object[]{sessionDetail.getTaskid(),
					sessionDetail.getUserid(),
					sessionDetail.getSessionid(),
					sessionDetail.getPageid(),
					sessionDetail.getActionTime(),
					sessionDetail.getSearchKeyword(),
					sessionDetail.getClickCategoryId(),
					sessionDetail.getClickProductId(),
					sessionDetail.getOrderCategoryIds(),
					sessionDetail.getOrderProductIds(),
					sessionDetail.getPayCategoryIds(),
					sessionDetail.getPayProductIds()};
			paramsList.add(params);
		}

		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeBatch(sql, paramsList);
	}
}