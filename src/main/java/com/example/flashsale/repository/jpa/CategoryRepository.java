package com.example.flashsale.repository.jpa;

import com.example.flashsale.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
