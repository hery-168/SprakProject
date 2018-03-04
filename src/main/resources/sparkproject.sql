/*
SQLyog Ultimate v11.42 (64 bit)
MySQL - 5.5.38 : Database - sparkproject
*********************************************************************
*/

/*!40101 SET NAMES utf8 */;

/*!40101 SET SQL_MODE=''*/;

/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
CREATE DATABASE /*!32312 IF NOT EXISTS*/`sparkproject` /*!40100 DEFAULT CHARACTER SET utf8 */;

USE `sparkproject`;

/*Table structure for table `session_aggr_stat` */

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

/*Data for the table `session_aggr_stat` */

insert  into `session_aggr_stat`(`task_id`,`session_count`,`1s_3s`,`4s_6s`,`7s_9s`,`10s_30s`,`30s_60s`,`1m_3m`,`3m_10m`,`10m_30m`,`30m`,`1_3`,`4_6`,`7_9`,`10_30`,`30_60`,`60`) values (1,993,0,0,0,0,0,0,0.01,0.05,0.92,0.08,0.14,0.17,0.6,0.01,0),(1,992,0,0,0,0,0,0,0.01,0.07,0.89,0.09,0.14,0.2,0.57,0,0),(1,989,0,0,0,0,0,0,0.01,0.07,0.9,0.09,0.14,0.19,0.58,0,0),(1,989,0,0,0,0,0,0,0.01,0.08,0.89,0.1,0.17,0.19,0.54,0.01,0),(1,990,0,0,0,0,0,0,0.01,0.07,0.9,0.1,0.16,0.19,0.54,0,0),(1,991,0,0,0,0,0,0,0.01,0.06,0.91,0.08,0.17,0.2,0.55,0,0),(1,988,0,0,0,0,0,0,0.01,0.08,0.89,0.1,0.15,0.19,0.55,0.01,0);

/*Table structure for table `session_detail` */

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

/*Data for the table `session_detail` */

/*Table structure for table `session_random_extract` */

DROP TABLE IF EXISTS `session_random_extract`;

CREATE TABLE `session_random_extract` (
  `task_id` int(11) DEFAULT NULL,
  `session_id` varchar(255) DEFAULT NULL,
  `start_time` varchar(50) DEFAULT NULL,
  `search_keywords` varchar(255) DEFAULT NULL,
  `click_category_id` varchar(255) DEFAULT NULL,
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

/*Data for the table `session_random_extract` */

insert  into `session_random_extract`(`task_id`,`session_id`,`start_time`,`search_keywords`,`click_category_id`) values (2,'111','2017-7-7','?????????','222'),(2,'111','2017-7-7','?????????','222');

/*Table structure for table `task` */

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

/*Data for the table `task` */

insert  into `task`(`task_id`,`task_name`,`create_time`,`start_time`,`finish_time`,`task_type`,`task_status`,`task_param`) values (1,'测试任务','2018-03-04','2018-03-04','2018-03-04','普通任务','进行中','{\"startAge\":[\"10\"],\"endAge\":[\"50\"],\"startDate\":[\"2018-03-04\"],\"endDate\":[\"2018-03-04\"]}');

/*Table structure for table `top10_category` */

DROP TABLE IF EXISTS `top10_category`;

CREATE TABLE `top10_category` (
  `task_id` int(11) DEFAULT NULL,
  `category_id` int(11) DEFAULT NULL,
  `click_count` int(11) DEFAULT NULL,
  `order_count` int(11) DEFAULT NULL,
  `pay_count` int(11) DEFAULT NULL,
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

/*Data for the table `top10_category` */

/*Table structure for table `top10_session` */

DROP TABLE IF EXISTS `top10_session`;

CREATE TABLE `top10_session` (
  `task_id` int(11) DEFAULT NULL,
  `category_id` int(11) DEFAULT NULL,
  `session_id` int(11) DEFAULT NULL,
  `click_count` int(11) DEFAULT NULL,
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

/*Data for the table `top10_session` */

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
