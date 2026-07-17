package com.pol.rag.module.kb.service;

import com.pol.rag.module.kb.entity.KbCategory;
import com.pol.rag.module.kb.mapper.KbCategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("分类服务")
class CategoryServiceTest {

    @Mock private KbCategoryMapper categoryMapper;

    @Spy
    private CategoryService categoryService = new CategoryService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(categoryService, "baseMapper", categoryMapper);
    }

    @Test
    @DisplayName("父分类为 null 时默认查询 parentId=0 的分类")
    void shouldDefaultParentIdToZeroWhenNull() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(new KbCategory()));

        List<KbCategory> result = categoryService.listByParent(null);

        assertThat(result).hasSize(1);
    }
}
