-- --------------------------------------------------------
-- Host:                         127.0.0.1
-- Server version:               8.0.37 - MySQL Community Server - GPL
-- Server OS:                    Win64
-- HeidiSQL Version:             12.7.0.6850
-- --------------------------------------------------------

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8 */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

-- Dumping structure for table email_service.batch_super
CREATE TABLE IF NOT EXISTS `batch_super` (
  `batch_id` bigint NOT NULL,
  `system_id` varchar(50) DEFAULT NULL,
  `smtp_id` int DEFAULT NULL,
  `ip_address` varchar(50) DEFAULT NULL,
  `subject` text,
  `body` text,
  `cc_recipients` text,
  `bcc_recipients` text,
  `attachments` text,
  `total_recipients` int DEFAULT NULL,
  `delay` double(10,5) NOT NULL DEFAULT '0.00000',
  `created_on` timestamp NULL DEFAULT NULL,
  `updated_on` timestamp NULL DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`batch_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Data exporting was unselected.

-- Dumping structure for table email_service.report_super
CREATE TABLE IF NOT EXISTS `report_super` (
  `msg_id` bigint NOT NULL,
  `batch_id` bigint DEFAULT '0',
  `recipient` varchar(50) DEFAULT NULL,
  `status` varchar(15) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `status_code` int DEFAULT '0',
  `received_on` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `submit_on` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `remarks` varchar(100) DEFAULT NULL,
  `partition_id` int GENERATED ALWAYS AS (cast(left(`msg_id`,6) as unsigned)) STORED NOT NULL,
  PRIMARY KEY (`msg_id`,`partition_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
/*!50100 PARTITION BY RANGE (`partition_id`)
(PARTITION p251124 VALUES LESS THAN (251125) ENGINE = InnoDB,
 PARTITION p251125 VALUES LESS THAN (251126) ENGINE = InnoDB,
 PARTITION p251126 VALUES LESS THAN (251127) ENGINE = InnoDB,
 PARTITION p251127 VALUES LESS THAN (251128) ENGINE = InnoDB,
 PARTITION pmax VALUES LESS THAN MAXVALUE ENGINE = InnoDB) */;

-- Data exporting was unselected.

-- Dumping structure for table email_service.smtp_config
CREATE TABLE IF NOT EXISTS `smtp_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `system_id` varchar(15) NOT NULL,
  `host` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `port` int NOT NULL,
  `encryption_type` enum('SSL','STARTTLS','NONE') NOT NULL,
  `email_user` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `email_password` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `verified` tinyint(1) NOT NULL DEFAULT (0),
  PRIMARY KEY (`id`)
) ENGINE=MyISAM AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Data exporting was unselected.

/*!40103 SET TIME_ZONE=IFNULL(@OLD_TIME_ZONE, 'system') */;
/*!40101 SET SQL_MODE=IFNULL(@OLD_SQL_MODE, '') */;
/*!40014 SET FOREIGN_KEY_CHECKS=IFNULL(@OLD_FOREIGN_KEY_CHECKS, 1) */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40111 SET SQL_NOTES=IFNULL(@OLD_SQL_NOTES, 1) */;
