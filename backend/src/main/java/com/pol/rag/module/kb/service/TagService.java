package com.pol.rag.module.kb.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pol.rag.module.kb.entity.KbTag;
import com.pol.rag.module.kb.mapper.KbTagMapper;
import org.springframework.stereotype.Service;

/**
 * 知识库标签服务，继承 MyBatis-Plus {@link ServiceImpl} 获得通用 CRUD 能力。
 */
@Service
public class TagService extends ServiceImpl<KbTagMapper, KbTag> {
}
