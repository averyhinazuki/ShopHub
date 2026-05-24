package com.example.flashsale.service;

import com.example.flashsale.dto.category.CategoryRequest;
import com.example.flashsale.dto.category.CategoryResponse;
import com.example.flashsale.entity.Category;
import com.example.flashsale.exception.ResourceNotFoundException;
import com.example.flashsale.repository.jpa.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public List<CategoryResponse> listAll() {
        return categoryRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public CategoryResponse create(CategoryRequest request) {
        Category category = new Category();
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        return toResponse(categoryRepository.save(category));
    }

    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));
        if (request.getName() != null)        category.setName(request.getName());
        if (request.getDescription() != null) category.setDescription(request.getDescription());
        return toResponse(categoryRepository.save(category));
    }

    // --- helpers ---

    public Category findEntityById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));
    }

    private CategoryResponse toResponse(Category c) {
        CategoryResponse res = new CategoryResponse();
        res.setId(c.getId());
        res.setName(c.getName());
        res.setDescription(c.getDescription());
        return res;
    }
}
