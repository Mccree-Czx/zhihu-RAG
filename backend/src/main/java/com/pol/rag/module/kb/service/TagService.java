package com.pol.rag.module.kb.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pol.rag.module.kb.entity.KbTag;
import com.pol.rag.module.kb.mapper.KbTagMapper;
import org.springframework.stereotype.Service;

@Service
public class TagService extends ServiceImpl<KbTagMapper, KbTag> {
}
