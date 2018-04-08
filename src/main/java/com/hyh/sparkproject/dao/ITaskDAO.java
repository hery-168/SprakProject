package com.hyh.sparkproject.dao;

import com.hyh.sparkproject.domain.Task;

/**
 * 任务管理DAO接口
 * @author hery
 *
 */
public interface ITaskDAO {
	
	/**
	 * 根据主键查询业务
	 */
	
	Task findById(long taskid);
	
}
