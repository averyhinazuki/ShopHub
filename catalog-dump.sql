-- MySQL dump 10.13  Distrib 5.7.17, for Win64 (x86_64)
--
-- Host: localhost    Database: flash_sale_db
-- ------------------------------------------------------
-- Server version	5.7.17-log

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Dumping data for table `categories`
--

LOCK TABLES `categories` WRITE;
/*!40000 ALTER TABLE `categories` DISABLE KEYS */;
INSERT INTO `categories` (`id`, `description`, `name`) VALUES (1,'Updated: phones, laptops, and accessories','Electronics'),(2,'Clothing, shoes, and accessories','Fashion'),(3,'Furniture, decor, and kitchen','Home & Living'),(4,'Equipment and activewear','Sports'),(5,'Skincare, makeup, and personal care','Beauty');
/*!40000 ALTER TABLE `categories` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `products`
--

LOCK TABLES `products` WRITE;
/*!40000 ALTER TABLE `products` DISABLE KEYS */;
INSERT INTO `products` (`id`, `created_at`, `description`, `image_url`, `name`, `price`, `status`, `category_id`) VALUES (1,'2026-05-24 15:20:27.655709','High-quality wireless headphones','https://res.cloudinary.com/dlxamalb1/image/upload/v1779779789/airpodsmax_azb5bs.png','AirPods Max',119.99,'ACTIVE',1),(2,'2026-05-24 23:49:46.908820','Only 1 in stock — used for compensation test',NULL,'Low Stock Widget',9.99,'ACTIVE',1),(3,'2026-05-26 14:43:54.000000','Noise-cancelling, 30hr battery',NULL,'Wireless Earbuds Pro',59.99,'ACTIVE',1),(4,'2026-05-26 14:43:54.000000','TKL, RGB, blue switches',NULL,'Mechanical Keyboard',89.99,'ACTIVE',1),(5,'2026-05-26 14:43:54.000000','HDMI, SD card, 3x USB-A',NULL,'USB-C Hub 7-in-1',34.99,'ACTIVE',1),(6,'2026-05-26 14:43:54.000000','Lightweight mesh, size 39-45',NULL,'Running Shoes',75.00,'ACTIVE',2),(7,'2026-05-26 14:43:54.000000','Cotton blend, unisex',NULL,'Oversized Hoodie',29.99,'ACTIVE',2),(8,'2026-05-26 14:43:54.000000','Stainless steel, water resistant',NULL,'Minimalist Watch',49.99,'ACTIVE',2),(9,'2026-05-26 14:43:54.000000','1500W, digital display',NULL,'Air Fryer 4L',65.00,'ACTIVE',3),(10,'2026-05-26 14:43:54.000000','5 compartments, eco-friendly',NULL,'Bamboo Desk Organizer',18.99,'ACTIVE',3),(11,'2026-05-26 14:43:54.000000','6mm thick, non-slip',NULL,'Yoga Mat',22.50,'ACTIVE',4),(12,'2026-05-26 14:43:54.000000','5 levels, with carry bag',NULL,'Resistance Bands Set',15.99,'ACTIVE',4),(13,'2026-05-26 14:43:54.000000','20%, with hyaluronic acid',NULL,'Vitamin C Serum',24.99,'ACTIVE',5),(14,'2026-05-26 14:43:54.000000','6 shades, long lasting',NULL,'Lip Tint Set',12.99,'ACTIVE',5),(15,'2026-05-26 15:59:14.562897','Latest iPhone 17 Pro','https://res.cloudinary.com/dlxamalb1/image/upload/v1779782343/flash-sale/products/vkt9sikxzxmkdt9tdo0u.png','iPhone 17 Pro',999.99,'ACTIVE',1);
/*!40000 ALTER TABLE `products` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `product_inventory`
--

LOCK TABLES `product_inventory` WRITE;
/*!40000 ALTER TABLE `product_inventory` DISABLE KEYS */;
INSERT INTO `product_inventory` (`id`, `available_stock`, `total_stock`, `product_id`) VALUES (1,29,50,1),(2,30,53,2),(3,21,21,15);
/*!40000 ALTER TABLE `product_inventory` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-07-18 20:40:53
