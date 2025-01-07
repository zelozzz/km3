package org.jeecg.modules.KM.service.impl;

import cn.hutool.core.collection.CollUtil;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.AnalyzeRequest;
import org.opensearch.client.opensearch.indices.AnalyzeResponse;
import org.opensearch.client.opensearch.indices.analyze.AnalyzeToken;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchPhraseQuery;
import org.opensearch.client.opensearch._types.query_dsl.MatchQuery;
import org.opensearch.client.opensearch._types.query_dsl.RangeQuery;
import org.opensearch.client.opensearch._types.query_dsl.PrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.cluster.GetComponentTemplateRequest;
import org.opensearch.client.opensearch.cluster.GetComponentTemplateResponse;
import org.opensearch.client.opensearch.indices.GetIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.GetIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.get_index_template.IndexTemplate;
import org.opensearch.client.opensearch.indices.get_index_template.IndexTemplateItem;
import org.opensearch.client.opensearch.indices.ExistsIndexTemplateRequest;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.PutIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.put_index_template.IndexTemplateMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.Translog;
import org.opensearch.client.opensearch._types.Time;
import org.opensearch.client.opensearch.indices.Alias;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.KeywordProperty;
import org.opensearch.client.opensearch._types.mapping.IntegerNumberProperty;
import org.opensearch.client.opensearch._types.mapping.TextProperty;
import org.opensearch.client.opensearch._types.mapping.DateProperty;
import org.opensearch.client.opensearch._types.mapping.IpProperty;
import org.opensearch.client.opensearch._types.mapping.TermVectorOption;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.KM.VO.KmDocEsVO;
import org.jeecg.modules.KM.common.rules.KMConstant;
import org.jeecg.modules.KM.entity.KmDoc;
import org.jeecg.modules.KM.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.List;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class KmEsMgntServiceImpl  implements IKmEsMgntService {

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private KmDocServiceImpl kmDocService;

    private boolean checkTemplateExists(String templateName) throws IOException {
        ExistsIndexTemplateRequest request = new ExistsIndexTemplateRequest.Builder()
                .name(templateName)
                .build();

        BooleanResponse response = openSearchClient.indices().existsIndexTemplate​(request);

        return response.value();
    }

    private  boolean checkIndexExists(String indexName)throws IOException {
        GetIndexRequest request = new GetIndexRequest.Builder()
            .index(indexName)
            .build();
        GetIndexResponse response = openSearchClient.indices().get(request);
        return !response.result().isEmpty();
    }

    //为初始化索引
    @Override
    public void initEXIndex(){
        KmDocEsVO kmEsVO = new KmDocEsVO();
        kmEsVO.setTitle("for init");
        kmEsVO.setDocId("1");
        Result<?> result = kmDocService.saveDocToEs(kmEsVO, "");
        if (result.isSuccess()) {
            String indexId = (String) result.getResult();
            if (indexId != null && !indexId.isEmpty()) {
                Result<?> result1 = kmDocService.deleteDocFromEs(indexId);
                if (result1.isSuccess()) {
                    log.info("init index success!");
                }
            }
        }
    }

    @Override
    public Result<?> syncReleasedDocToES(){
        Result result = Result.OK("初始化:同步发布文档到ES");;

        List<KmDoc> releasedDocs = kmDocService.getReleasedDocs();
        for (KmDoc releasedDoc : releasedDocs) {
            kmDocService.indexDocSync(releasedDoc);
        }
        return result;
    }

    @Override
    public Result<?> initTemplateAndSyncDocs() throws IOException {
        Result result = Result.OK("创建模版");;
        if(!checkTemplateExists(KMConstant.DocIndexName)){
            result = initKmDocTemplate();
            if(!result.isSuccess()){
                log.error(result.getMessage());
                return result;
            }
            //首次建索引
            initEXIndex();
        }
        if (!checkIndexExists(KMConstant.DocIndexName)) {
            log.info("start sync docs record to ES...");
            //for手工删索引，重新对知识入库
            syncReleasedDocToES();
        }
        if(!checkTemplateExists(KMConstant.KMSearchRecordIndexName)){
            result = initKmSearchRecordTemplate();
            if(!result.isSuccess()){
                log.error(result.getMessage());
                return result;
            }
        }
        if(!checkTemplateExists(KMConstant.DocVisitIndexName)){
            result = initKmDocVisitTemplate();
            if(!result.isSuccess()){
                log.error(result.getMessage());
                return result;
            }
        }
        if(!result.isSuccess()){
            log.error("初始化ES模版失败：",result.getMessage());
        }
        return result;
    }

    @Override
    public Result<?> initKmDocTemplate() throws IOException {

        // 构建IndexSettings对象
        Translog translog = new Translog.Builder()
            .durability("async")
            .syncInterval(Time.of(t -> t.time("120s")))
            .build();
            
        IndexSettings indexSettings = new IndexSettings.Builder()
            .numberOfShards("2")
            .numberOfReplicas("0")
            .maxResultWindow(1000)
            .translog(translog)
            .refreshInterval(Time.of(t -> t.time("10s")))
            .build();

        // 构建aliases配置
        Map<String, Alias> alias = Collections.singletonMap(KMConstant.DocIndexAliasName, 
                                    new Alias.Builder().isWriteIndex(true).build());

        // 构建mappings配置
        Map<String, Property> properties = new HashMap<>();
        Property dateProp = new Property.Builder()
            .date(new DateProperty.Builder().format("yyyy-MM-dd||epoch_millis||strict_date_optional_time").build())
            .build();

        Property txtProp = new Property.Builder()
            .text(new TextProperty.Builder().analyzer("ik_max_word").termVector(TermVectorOption.WithPositionsOffsets).build())
            .build();

        properties.put("createTime", dateProp);
        properties.put("title", txtProp);
        properties.put("content", txtProp);

        Property intProp = new Property.Builder()
            .integer(new IntegerNumberProperty.Builder().build())
            .build();
        properties.put("releaseFlag", intProp);
        properties.put("publicRemark", intProp);
        properties.put("category", intProp);
        properties.put("businessTypes", intProp);
        Property prop = new Property.Builder()
            .keyword(new KeywordProperty.Builder().build())
            .build();
        properties.put("topicCodes", prop);
        properties.put("id", prop);
        properties.put("fileNo", prop);
        properties.put("orgCode", prop);
        properties.put("keywords", prop);
        properties.put("topicCodes", prop);

        TypeMapping tm = new TypeMapping.Builder()
            .properties(properties)
            .build();

        // 构建IndexTemplateMapping对象
        IndexTemplateMapping itmapping = new IndexTemplateMapping.Builder()
                .settings(indexSettings)
                .aliases(alias)
                .mappings(tm) // 使用properties代替整个mappings对象
                .build();

        // 构建PutIndexTemplateRequest请求
        PutIndexTemplateRequest request = new PutIndexTemplateRequest.Builder()
                .name(KMConstant.DocIndexName)
                .indexPatterns(Collections.singletonList(KMConstant.DocIndexName + "*"))
                .template(itmapping)
                .priority(3)
                .build();

        // 执行请求并获取响应
        PutIndexTemplateResponse response = openSearchClient.indices().putIndexTemplate(request);

        if (response.acknowledged()) {
            log.info(KMConstant.DocIndexName + "模版创建成功!");
            return Result.ok("创建模版成功!");
        } else {
            log.error(KMConstant.DocIndexName + "模版创建失败!");
            return Result.error("创建模版失败!");
        }
    }


    @Override
    public Result<?> initKmDocVisitTemplate() throws IOException {

        // 构建IndexSettings对象
        Translog translog = new Translog.Builder()
            .durability("async")
            .syncInterval(Time.of(t -> t.time("600s")))
            .build();
            
        IndexSettings indexSettings = new IndexSettings.Builder()
            .numberOfShards("2")
            .numberOfReplicas("0")
            .maxResultWindow(1000)
            .translog(translog)
            .refreshInterval(Time.of(t -> t.time("60s")))
            .build();

        // 构建aliases配置
        Map<String, Alias> alias = Collections.singletonMap(KMConstant.DocVisitIndexAliasName, 
                                    new Alias.Builder().isWriteIndex(true).build());

        // 构建mappings配置
        Map<String, Property> properties = new HashMap<>();
        Property dateProp = new Property.Builder()
            .date(new DateProperty.Builder().format("yyyy-MM-dd HH:mm:ss").build())
            .build();

        Property txtProp = new Property.Builder()
            .text(new TextProperty.Builder().analyzer("ik_smart").build())
            .build();

        properties.put("createTime", dateProp);
        properties.put("keywords", txtProp);

        Property intProp = new Property.Builder()
            .integer(new IntegerNumberProperty.Builder().build())
            .build();
        properties.put("visitType", intProp);

        Property prop = new Property.Builder()
            .keyword(new KeywordProperty.Builder().build())
            .build();
        properties.put("createBy", prop);
        properties.put("id", prop);

        properties.put("keywordsMax", prop);
        Property ipProp = new Property.Builder()
            .ip(new IpProperty.Builder().build())
            .build();
        properties.put("sourceIp", ipProp);

        TypeMapping tm = new TypeMapping.Builder()
            .properties(properties)
            .build();

        // 构建IndexTemplateMapping对象
        IndexTemplateMapping itmapping = new IndexTemplateMapping.Builder()
                .settings(indexSettings)
                .aliases(alias)
                .mappings(tm) // 使用properties代替整个mappings对象
                .build();

        // 构建PutIndexTemplateRequest请求
        PutIndexTemplateRequest request = new PutIndexTemplateRequest.Builder()
                .name(KMConstant.DocVisitIndexName)
                .indexPatterns(Collections.singletonList(KMConstant.DocVisitIndexName + "*"))
                .template(itmapping)
                .priority(1)
                .build();

        // 执行请求并获取响应
        PutIndexTemplateResponse response = openSearchClient.indices().putIndexTemplate(request);

        if (response.acknowledged()) {
            log.info(KMConstant.DocVisitIndexName + "模版创建成功!");
            return Result.ok("创建模版成功!");
        } else {
            log.error(KMConstant.DocVisitIndexName + "模版创建失败!");
            return Result.error("创建模版失败!");
        }
    }

    @Override
    public Result<?> initKmSearchRecordTemplate() throws IOException {

        // 构建IndexSettings对象
        Translog translog = new Translog.Builder()
            .durability("async")
            .syncInterval(Time.of(t -> t.time("600s")))
            .build();
            
        IndexSettings indexSettings = new IndexSettings.Builder()
            .numberOfShards("2")
            .numberOfReplicas("0")
            .maxResultWindow(10000)
            .translog(translog)
            .refreshInterval(Time.of(t -> t.time("60s")))
            .build();

        // 构建aliases配置
        Map<String, Alias> alias = Collections.singletonMap(KMConstant.KMSearchRecordIndexAliasName, 
                                    new Alias.Builder().isWriteIndex(true).build());

        // 构建mappings配置
        Map<String, Property> properties = new HashMap<>();
        Property dateProp = new Property.Builder()
            .date(new DateProperty.Builder().format("yyyy-MM-dd HH:mm:ss").build())
            .build();

        Property txtProp = new Property.Builder()
            .text(new TextProperty.Builder().analyzer("ik_smart").build())
            .build();

        properties.put("createTime", dateProp);
        properties.put("title", txtProp);
        properties.put("content", txtProp);
        properties.put("keywords", txtProp);


        Property prop = new Property.Builder()
            .keyword(new KeywordProperty.Builder().build())
            .build();
        properties.put("createBy", prop);
        properties.put("topicCodes", prop);

        properties.put("keywordsMax", prop);
        Property ipProp = new Property.Builder()
            .ip(new IpProperty.Builder().build())
            .build();
        properties.put("sourceIp", ipProp);

        TypeMapping tm = new TypeMapping.Builder()
            .properties(properties)
            .build();

        // 构建IndexTemplateMapping对象
        IndexTemplateMapping itmapping = new IndexTemplateMapping.Builder()
                .settings(indexSettings)
                .aliases(alias)
                .mappings(tm) // 使用properties代替整个mappings对象
                .build();

        // 构建PutIndexTemplateRequest请求
        PutIndexTemplateRequest request = new PutIndexTemplateRequest.Builder()
                .name(KMConstant.KMSearchRecordIndexName)
                .indexPatterns(Collections.singletonList(KMConstant.KMSearchRecordIndexName + "*"))
                .template(itmapping)
                .build();

        // 执行请求并获取响应
        PutIndexTemplateResponse response = openSearchClient.indices().putIndexTemplate(request);

        if (response.acknowledged()) {
            log.info(KMConstant.KMSearchRecordIndexName + "模版创建成功!");
            return Result.ok("创建模版成功!");
        } else {
            log.error(KMConstant.KMSearchRecordIndexName + "模版创建失败!");
            return Result.error("创建模版失败!");
        }
    }
}
