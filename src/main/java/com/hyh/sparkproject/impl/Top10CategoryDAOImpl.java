package com.hyh.sparkproject.impl;

import com.hyh.sparkproject.dao.ITop10CategoryDAO;
import com.hyh.sparkproject.domain.Top10Category;
import com.hyh.sparkproject.jdbc.JDBCHelper;

/**
 * top10品类DAO实现
 * @author Erik
 *
 */
public class Top10CategoryDAOImpl implements ITop10CategoryDAO {

	public void insert(Top10Category category) {
		String sql = "insert into top10_category values(?,?,?,?,?)";
		Object[] params = new Object[]{
				category.getTaskid(),
				category.getCategoryid(),
				category.getClickCount(),
				category.getOrderCount(),
				category.getPayCount()};
		
		JDBCHelper jdbcHelper = JDBCHelper.getInstance();
		jdbcHelper.executeUpdate(sql, params);
		
	}

}
