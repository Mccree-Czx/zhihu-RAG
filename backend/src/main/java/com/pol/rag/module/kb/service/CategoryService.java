package com.pol.rag.module.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pol.rag.module.kb.entity.KbCategory;
import com.pol.rag.module.kb.mapper.KbCategoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryService extends ServiceImpl<KbCategoryMapper, KbCategory> {

    public List<KbCategory> listByParent(Long parentId) {
        return list(new LambdaQueryWrapper<KbCategory>()
                .eq(KbCategory::getParentId, parentId == null ? 0L : parentId)
                .orderByAsc(KbCategory::getSort));
    }
}
