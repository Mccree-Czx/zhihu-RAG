package com.pol.rag.module.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pol.rag.module.kb.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    @Select("SELECT * FROM kb_chunk WHERE document_id = #{documentId} ORDER BY chunk_index")
    List<KbChunk> selectByDocumentId(Long documentId);
}
