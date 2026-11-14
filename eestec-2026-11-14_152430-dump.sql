-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Host: localhost    Database: filegrid_ppc
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `citiri_contoare`
--

DROP TABLE IF EXISTS `citiri_contoare`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `citiri_contoare` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `idempotency_key` varchar(64) NOT NULL,
  `contor_id` int NOT NULL,
  `fisier_sursa_id` bigint DEFAULT NULL,
  `timestamp_citire` datetime NOT NULL,
  `index_kwh` decimal(12,3) NOT NULL,
  `tensiune_v` decimal(6,2) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idempotency_key` (`idempotency_key`),
  KEY `idx_contor_timp` (`contor_id`,`timestamp_citire`),
  KEY `fisier_sursa_id` (`fisier_sursa_id`),
  CONSTRAINT `citiri_contoare_ibfk_1` FOREIGN KEY (`contor_id`) REFERENCES `contoare` (`id`),
  CONSTRAINT `citiri_contoare_ibfk_2` FOREIGN KEY (`fisier_sursa_id`) REFERENCES `fisiere_metadate` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `citiri_contoare`
--

LOCK TABLES `citiri_contoare` WRITE;
/*!40000 ALTER TABLE `citiri_contoare` DISABLE KEYS */;
INSERT INTO `citiri_contoare` (`id`, `idempotency_key`, `contor_id`, `fisier_sursa_id`, `timestamp_citire`, `index_kwh`, `tensiune_v`) VALUES (1,'REQ-20261114-0001',1,1,'2026-11-14 08:00:00',12450.500,230.20),(2,'REQ-20261114-0002',2,2,'2026-11-14 08:00:00',8320.100,229.80),(3,'REQ-20261114-0003',3,3,'2026-11-14 08:00:00',19410.000,231.00),(4,'REQ-20261114-0004',5,4,'2026-11-14 08:00:00',5412.700,228.50),(5,'REQ-20261114-0005',6,5,'2026-11-14 08:00:00',11205.300,230.10),(6,'REQ-20261114-0006',7,6,'2026-11-14 08:00:00',7630.900,227.90),(7,'REQ-20261114-0007',8,7,'2026-11-14 08:00:00',15890.400,232.40),(8,'REQ-20261114-0008',9,8,'2026-11-14 08:00:00',9102.200,231.10),(9,'REQ-20261114-0009',1,9,'2026-11-14 09:00:00',12452.100,230.00),(10,'REQ-20261114-0010',5,10,'2026-11-14 09:00:00',5414.200,229.00),(11,'REQ-20261114-0011',8,11,'2026-11-14 09:00:00',15893.000,231.80),(12,'REQ-20261114-0012',2,12,'2026-11-14 09:00:00',8321.800,229.50),(13,'REQ-20261114-0013',3,13,'2026-11-14 09:00:00',19412.400,230.70),(14,'REQ-20261114-0014',6,14,'2026-11-14 09:00:00',11207.100,230.40),(15,'REQ-20261114-0015',7,NULL,'2026-11-14 09:00:00',7632.350,212.40),(16,'REQ-20261114-0016',9,NULL,'2026-11-14 09:00:00',9103.800,231.50),(17,'REQ-20261114-0017',1,NULL,'2026-11-14 10:00:00',12453.950,229.90),(18,'REQ-20261114-0018',2,NULL,'2026-11-14 10:00:00',8323.400,246.80),(19,'REQ-20261114-0019',5,NULL,'2026-11-14 10:00:00',5415.900,228.70),(20,'REQ-20261114-0020',8,NULL,'2026-11-14 10:00:00',15895.600,232.00);
/*!40000 ALTER TABLE `citiri_contoare` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `contoare`
--

DROP TABLE IF EXISTS `contoare`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `contoare` (
  `id` int NOT NULL AUTO_INCREMENT,
  `serie_contor` varchar(50) NOT NULL,
  `regiune` enum('Muntenia','Banat','Dobrogea') NOT NULL,
  `localitate` varchar(100) DEFAULT NULL,
  `status` enum('ACTIV','INACTIV','DEFECT') DEFAULT 'ACTIV',
  PRIMARY KEY (`id`),
  UNIQUE KEY `serie_contor` (`serie_contor`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `contoare`
--

LOCK TABLES `contoare` WRITE;
/*!40000 ALTER TABLE `contoare` DISABLE KEYS */;
INSERT INTO `contoare` (`id`, `serie_contor`, `regiune`, `localitate`, `status`) VALUES (1,'MTR-MUN-001','Muntenia','București','ACTIV'),(2,'MTR-MUN-002','Muntenia','Ploiești','ACTIV'),(3,'MTR-MUN-003','Muntenia','Pitești','ACTIV'),(4,'MTR-MUN-004','Muntenia','Târgoviște','DEFECT'),(5,'MTR-BAN-005','Banat','Timișoara','ACTIV'),(6,'MTR-BAN-006','Banat','Arad','ACTIV'),(7,'MTR-BAN-007','Banat','Reșița','ACTIV'),(8,'MTR-DOB-008','Dobrogea','Constanța','ACTIV'),(9,'MTR-DOB-009','Dobrogea','Tulcea','ACTIV'),(10,'MTR-DOB-010','Dobrogea','Mangalia','INACTIV');
/*!40000 ALTER TABLE `contoare` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `fisiere_continut_db`
--

DROP TABLE IF EXISTS `fisiere_continut_db`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fisiere_continut_db` (
  `fisier_id` bigint NOT NULL,
  `continut_binar` mediumblob NOT NULL,
  PRIMARY KEY (`fisier_id`),
  CONSTRAINT `fisiere_continut_db_ibfk_1` FOREIGN KEY (`fisier_id`) REFERENCES `fisiere_metadate` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `fisiere_continut_db`
--

LOCK TABLES `fisiere_continut_db` WRITE;
/*!40000 ALTER TABLE `fisiere_continut_db` DISABLE KEYS */;
INSERT INTO `fisiere_continut_db` (`fisier_id`, `continut_binar`) VALUES (1,0x7B22636F6E746F72223A224D54522D4D554E2D303031222C226B7768223A31323435302E352C2276223A3233302E327D),(2,0x7B22636F6E746F72223A224D54522D4D554E2D303032222C226B7768223A383332302E312C2276223A3232392E387D),(3,0x7B22636F6E746F72223A224D54522D4D554E2D303033222C226B7768223A31393431302E302C2276223A3233312E307D),(4,0x7B22636F6E746F72223A224D54522D42414E2D303035222C226B7768223A353431322E372C2276223A3232382E357D),(5,0x7B22636F6E746F72223A224D54522D42414E2D303036222C226B7768223A31313230352E332C2276223A3233302E317D),(6,0x7B22636F6E746F72223A224D54522D42414E2D303037222C226B7768223A373633302E392C2276223A3232372E397D),(7,0x7B22636F6E746F72223A224D54522D444F422D303038222C226B7768223A31353839302E342C2276223A3233322E347D),(8,0x7B22636F6E746F72223A224D54522D444F422D303039222C226B7768223A393130322E322C2276223A3233312E317D),(9,0x7B22636F6E746F72223A224D54522D4D554E2D303031222C226B7768223A31323435322E312C2276223A3233302E307D),(10,0x7B22636F6E746F72223A224D54522D42414E2D303035222C226B7768223A353431342E322C2276223A3232392E307D),(11,0x7B22636F6E746F72223A224D54522D444F422D303038222C226B7768223A31353839332E302C2276223A3233312E387D),(12,0x7B22636F6E746F72223A224D54522D4D554E2D303032222C226B7768223A383332312E382C2276223A3232392E357D),(13,0x7B22636F6E746F72223A224D54522D4D554E2D303033222C226B7768223A31393431322E342C2276223A3233302E377D),(14,0x7B22636F6E746F72223A224D54522D42414E2D303036222C226B7768223A31313230372E312C2276223A3233302E347D);
/*!40000 ALTER TABLE `fisiere_continut_db` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `fisiere_metadate`
--

DROP TABLE IF EXISTS `fisiere_metadate`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fisiere_metadate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uuid_fisier` char(36) NOT NULL,
  `nume_original` varchar(255) NOT NULL,
  `categorie` enum('CITIRI_CONTOARE','AVARII','RACORDARE','VIDEO_DRONA') NOT NULL,
  `dimensiune_bytes` bigint NOT NULL,
  `checksum_sha256` char(64) NOT NULL,
  `storage_folosit` enum('MYSQL_BLOB','S3','FTP') NOT NULL,
  `cale_sau_referinta` varchar(500) NOT NULL,
  `status_transfer` enum('IN_CURS','CONFIRMAT','EROARE') DEFAULT 'CONFIRMAT',
  `creat_la` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uuid_fisier` (`uuid_fisier`),
  KEY `idx_categorie_data` (`categorie`,`creat_la`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `fisiere_metadate`
--

LOCK TABLES `fisiere_metadate` WRITE;
/*!40000 ALTER TABLE `fisiere_metadate` DISABLE KEYS */;
INSERT INTO `fisiere_metadate` (`id`, `uuid_fisier`, `nume_original`, `categorie`, `dimensiune_bytes`, `checksum_sha256`, `storage_folosit`, `cale_sau_referinta`, `status_transfer`, `creat_la`) VALUES (1,'11111111-1111-1111-1111-111111111101','citire_mun_001.json','CITIRI_CONTOARE',1420,'0db2b6e31d6ae3c1d6dffb13639edcccbc30d02266910fadb3ff6522e7cc9ce6','MYSQL_BLOB','db://fisiere_continut_db/1','CONFIRMAT','2026-11-14 13:19:41'),(2,'11111111-1111-1111-1111-111111111102','citire_mun_002.json','CITIRI_CONTOARE',1380,'3a118e44b499428e0c261326fba22f4f672923011a1bc8619b7ad27f1476540a','MYSQL_BLOB','db://fisiere_continut_db/2','CONFIRMAT','2026-11-14 13:19:41'),(3,'11111111-1111-1111-1111-111111111103','citire_mun_003.json','CITIRI_CONTOARE',1510,'2f450294aef9f666f7f6ae5202bace9221baab72eb7654c5bc11b01cb0ef5945','MYSQL_BLOB','db://fisiere_continut_db/3','CONFIRMAT','2026-11-14 13:19:41'),(4,'11111111-1111-1111-1111-111111111104','citire_ban_005.json','CITIRI_CONTOARE',1290,'57d15c5b8dd8ae4f438b5977159d1ae7e693ec8f1fb8d839b5bc3550e78a0344','MYSQL_BLOB','db://fisiere_continut_db/4','CONFIRMAT','2026-11-14 13:19:41'),(5,'11111111-1111-1111-1111-111111111105','citire_ban_006.json','CITIRI_CONTOARE',1440,'92ce1842d04a18a2e77a3e06c7dfa30c7bf92c726e13666c1720794c87b9de83','MYSQL_BLOB','db://fisiere_continut_db/5','CONFIRMAT','2026-11-14 13:19:41'),(6,'11111111-1111-1111-1111-111111111106','citire_ban_007.json','CITIRI_CONTOARE',1350,'45814e8f1180bd4b830487c7398559d8491d88ad83fdb6b4f695f1f095b3c711','MYSQL_BLOB','db://fisiere_continut_db/6','CONFIRMAT','2026-11-14 13:19:41'),(7,'11111111-1111-1111-1111-111111111107','citire_dob_008.json','CITIRI_CONTOARE',1490,'1d979351da710e7357c11f1899aa4bdbf34cb5365ec25fc9a7463af864aa82d7','MYSQL_BLOB','db://fisiere_continut_db/7','CONFIRMAT','2026-11-14 13:19:41'),(8,'11111111-1111-1111-1111-111111111108','citire_dob_009.json','CITIRI_CONTOARE',1410,'ecced8a9444f4b156ffb0fc39894704e916726026fdeedbea870ac938c78ffb0','MYSQL_BLOB','db://fisiere_continut_db/8','CONFIRMAT','2026-11-14 13:19:41'),(9,'11111111-1111-1111-1111-111111111109','citire_mun_001_v2.json','CITIRI_CONTOARE',1425,'566f791953cb2df3a682289fbd606b6f7950178b6af7b94b3e74e564d8ed8ba5','MYSQL_BLOB','db://fisiere_continut_db/9','CONFIRMAT','2026-11-14 13:19:41'),(10,'11111111-1111-1111-1111-111111111110','citire_ban_005_v2.json','CITIRI_CONTOARE',1300,'9cdf10a398b610c92cc3c30faa90bb1e89e307f91a045aaf11ee5d3ef529990a','MYSQL_BLOB','db://fisiere_continut_db/10','CONFIRMAT','2026-11-14 13:19:41'),(11,'11111111-1111-1111-1111-111111111111','citire_dob_008_v2.json','CITIRI_CONTOARE',1505,'a2aeb54532f4cb659a43b23f878bd5e109b52703cdece1832098bee41182d359','MYSQL_BLOB','db://fisiere_continut_db/11','CONFIRMAT','2026-11-14 13:19:41'),(12,'11111111-1111-1111-1111-111111111112','citire_mun_002_v2.json','CITIRI_CONTOARE',1390,'4f192c86560271d5bbe4fc155c6c49540b511e9fa9008f491cae2aa0472082bf','MYSQL_BLOB','db://fisiere_continut_db/12','CONFIRMAT','2026-11-14 13:19:41'),(13,'11111111-1111-1111-1111-111111111113','citire_mun_003_v2.json','CITIRI_CONTOARE',1520,'8c3f5f1f71098bd76b51e766660760b1fb9dc0fc7d2d864e8294d5b6eb192605','MYSQL_BLOB','db://fisiere_continut_db/13','CONFIRMAT','2026-11-14 13:19:41'),(14,'11111111-1111-1111-1111-111111111114','citire_ban_006_v2.json','CITIRI_CONTOARE',1455,'a9f9391c783591c8e102685f24f3b6f161a2e65975e674249d4fa746bcd08835','MYSQL_BLOB','db://fisiere_continut_db/14','CONFIRMAT','2026-11-14 13:19:41'),(15,'11111111-1111-1111-1111-111111111115','avarie_statie_nord.log','AVARII',4520000,'48ec0719fb011acb28079351e37dc04032492e6cd98e621a961aad26dfcf1bf7','S3','s3://avarii/2026/11/avarie_statie_nord.log','CONFIRMAT','2026-11-14 13:19:41'),(16,'11111111-1111-1111-1111-111111111116','avarie_furtuna_timis.dat','AVARII',8910000,'4bba41d27b42dcfcd2d9ae5564186ea78f5124b0108f03860b28fef5ea85f176','S3','s3://avarii/2026/11/avarie_furtuna_timis.dat','CONFIRMAT','2026-11-14 13:19:41'),(17,'11111111-1111-1111-1111-111111111117','cerere_racordare_8821.pdf','RACORDARE',2150000,'c5018c4391709b4ff95c95a30a5bf8b17e85b13df54b6435e3fab3fe37fdaa0f','S3','s3://racordari/2026/11/cerere_racordare_8821.pdf','CONFIRMAT','2026-11-14 13:19:41'),(18,'11111111-1111-1111-1111-111111111118','aviz_tehnic_9940.pdf','RACORDARE',1840000,'3864aba6666cd6d949a4cc2d49ad5a65f797c066d427a8a2a8c29f491f302c98','S3','s3://racordari/2026/11/aviz_tehnic_9940.pdf','CONFIRMAT','2026-11-14 13:19:41'),(19,'11111111-1111-1111-1111-111111111119','inspectie_lea_110kv_01.mp4','VIDEO_DRONA',540000000,'b634e94db4683e4bb78422eda2c49314857d429b9bd31eda513dbfc8cdd29343','FTP','/ftp/drone/2026/11/inspectie_lea_110kv_01.mp4','CONFIRMAT','2026-11-14 13:19:41'),(20,'11111111-1111-1111-1111-111111111120','inspectie_lea_400kv_02.mp4','VIDEO_DRONA',980000000,'93d8a998c34b1aca00f002cb233a448100772225440edb784546d4023ba6af76','FTP','/ftp/drone/2026/11/inspectie_lea_400kv_02.mp4','CONFIRMAT','2026-11-14 13:19:41');
/*!40000 ALTER TABLE `fisiere_metadate` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `politici_rutare`
--

DROP TABLE IF EXISTS `politici_rutare`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `politici_rutare` (
  `id` int NOT NULL AUTO_INCREMENT,
  `categorie` enum('CITIRI_CONTOARE','AVARII','RACORDARE','VIDEO_DRONA') NOT NULL,
  `storage_tinta` enum('MYSQL_BLOB','S3','FTP') NOT NULL,
  `criptare_activa` tinyint(1) DEFAULT '0',
  `actualizat_la` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `categorie` (`categorie`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `politici_rutare`
--

LOCK TABLES `politici_rutare` WRITE;
/*!40000 ALTER TABLE `politici_rutare` DISABLE KEYS */;
INSERT INTO `politici_rutare` (`id`, `categorie`, `storage_tinta`, `criptare_activa`, `actualizat_la`) VALUES (1,'CITIRI_CONTOARE','MYSQL_BLOB',0,'2026-11-14 13:18:20'),(2,'AVARII','S3',0,'2026-11-14 13:18:20'),(3,'RACORDARE','S3',1,'2026-11-14 13:18:20'),(4,'VIDEO_DRONA','FTP',0,'2026-11-14 13:18:20');
/*!40000 ALTER TABLE `politici_rutare` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping routines for database 'filegrid_ppc'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-11-14 15:24:31
