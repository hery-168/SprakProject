/*
Navicat MySQL Data Transfer

Source Server         : localhost
Source Server Version : 50635
Source Host           : localhost:3306
Source Database       : sparkproject

Target Server Type    : MYSQL
Target Server Version : 50635
File Encoding         : 65001

Date: 2018-03-06 15:39:35
*/

SET FOREIGN_KEY_CHECKS=0;

-- ----------------------------
-- Table structure for session_aggr_stat
-- ----------------------------
DROP TABLE IF EXISTS `session_aggr_stat`;
CREATE TABLE `session_aggr_stat` (
  `task_id` int(11) DEFAULT NULL,
  `session_count` int(11) DEFAULT NULL,
  `1s_3s` double DEFAULT NULL,
  `4s_6s` double DEFAULT NULL,
  `7s_9s` double DEFAULT NULL,
  `10s_30s` double DEFAULT NULL,
  `30s_60s` double DEFAULT NULL,
  `1m_3m` double DEFAULT NULL,
  `3m_10m` double DEFAULT NULL,
  `10m_30m` double DEFAULT NULL,
  `30m` double DEFAULT NULL,
  `1_3` double DEFAULT NULL,
  `4_6` double DEFAULT NULL,
  `7_9` double DEFAULT NULL,
  `10_30` double DEFAULT NULL,
  `30_60` double DEFAULT NULL,
  `60` double DEFAULT NULL,
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
-- Records of session_aggr_stat
-- ----------------------------

-- ----------------------------
-- Table structure for session_detail
-- ----------------------------
DROP TABLE IF EXISTS `session_detail`;
CREATE TABLE `session_detail` (
  `task_id` int(11) DEFAULT NULL,
  `user_id` int(11) DEFAULT NULL,
  `session_id` varchar(255) DEFAULT NULL,
  `page_id` int(11) DEFAULT NULL,
  `action_time` varchar(255) DEFAULT NULL,
  `search_keyword` varchar(255) DEFAULT NULL,
  `click_category_id` int(11) DEFAULT NULL,
  `click_product_id` int(11) DEFAULT NULL,
  `order_category_ids` varchar(255) DEFAULT NULL,
  `order_product_ids` varchar(255) DEFAULT NULL,
  `pay_category_ids` varchar(255) DEFAULT NULL,
  `pay_product_ids` varchar(255) DEFAULT NULL,
  KEY `idx_task_id` (`task_id`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
-- Records of session_detail
-- ----------------------------

-- ----------------------------
-- Table structure for session_random_extract
-- ----------------------------
DROP TABLE IF EXISTS `session_random_extract`;
CREATE TABLE `session_random_extract` (
  `task_id` int(11) DEFAULT NULL,
  `session_id` varchar(255) DEFAULT NULL,
  `start_time` varchar(50) DEFAULT NULL,
  `search_keywords` varchar(255) DEFAULT NULL,
  `click_category_id` varchar(255) DEFAULT NULL,
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
-- Records of session_random_extract
-- ----------------------------

-- ----------------------------
-- Table structure for task
-- ----------------------------
DROP TABLE IF EXISTS `task`;
CREATE TABLE `task` (
  `task_id` int(11) NOT NULL AUTO_INCREMENT,
  `task_name` varchar(255) DEFAULT NULL,
  `create_time` varchar(255) DEFAULT NULL,
  `start_time` varchar(255) DEFAULT NULL,
  `finish_time` varchar(255) DEFAULT NULL,
  `task_type` varchar(255) DEFAULT NULL,
  `task_status` varchar(255) DEFAULT NULL,
  `task_param` text,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8;

-- ----------------------------
-- Records of task
-- ----------------------------
INSERT INTO `task` VALUES ('1', '测试任务', '2018-03-04', '2018-03-04', '2018-03-04', '普通任务', '进行中', '{\"startAge\":[\"10\"],\"endAge\":[\"50\"],\"startDate\":[\"2018-03-06\"],\"endDate\":[\"2018-03-06\"]}');

-- ----------------------------
-- Table structure for top10_category
-- ----------------------------
DROP TABLE IF EXISTS `top10_category`;
CREATE TABLE `top10_category` (
  `task_id` int(11) DEFAULT NULL,
  `category_id` int(11) DEFAULT NULL,
  `click_count` int(11) DEFAULT NULL,
  `order_count` int(11) DEFAULT NULL,
  `pay_count` int(11) DEFAULT NULL,
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
-- Records of top10_category
-- ----------------------------

-- ----------------------------
-- Table structure for top10_session
-- ----------------------------
DROP TABLE IF EXISTS `top10_session`;
CREATE TABLE `top10_session` (
  `task_id` int(11) DEFAULT NULL,
  `category_id` int(11) DEFAULT NULL,
  `session_id` varchar(50) DEFAULT NULL,
  `click_count` int(11) DEFAULT NULL,
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
-- Records of top10_session
-- ----------------------------
