package com.pol.rag.module.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pol.rag.module.kb.entity.KbCategory;
import com.pol.rag.module.kb.mapper.KbCategoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库分类服务，继承 MyBatis-Plus {@link ServiceImpl} 获得通用 CRUD 能力。
 *
 * <p>新增方法：{@link #listByParent} 按父分类 ID 查询子分类（null 视为 0）。</p>
 */
@Service
public class CategoryService extends ServiceImpl<KbCategoryMapper, KbCategory> {

    /**
     * 按父分类 ID 查询子分类列表，null 视为 parentId=0。
     *
     * @param parentId 父分类 ID，为 null 时默认查询顶级分类
     * @return 分类列表
     */
    public List<KbCategory> listByParent(Long parentId) {
        return list(new LambdaQueryWrapper<KbCategory>()
                .eq(KbCategory::getParentId, parentId == null ? 0L : parentId)
                .orderByAsc(KbCategory::getSort));
    }
}
