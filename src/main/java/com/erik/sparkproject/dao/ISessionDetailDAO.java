package com.erik.sparkproject.dao;

import com.erik.sparkproject.domain.SessionDetail;

import java.util.List;

/**
 * session明细接口
 * @author Erik
 *
 */
public interface ISessionDetailDAO {
	
	/**
	 * 插入一条session明细数据
	 * @param sessionDetail
	 */
	void insert(SessionDetail sessionDetail);

	/**
	 * 批量插入session明细数据
	 * @param sessionDetails
	 */
	void insertBatch(List<SessionDetail> sessionDetails);

}
