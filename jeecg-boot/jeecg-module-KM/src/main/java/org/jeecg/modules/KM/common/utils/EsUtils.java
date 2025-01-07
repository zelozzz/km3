package org.jeecg.modules.KM.common.utils;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
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
import org.opensearch.client.json.JsonData;

import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.KM.VO.KmDocEsFilterParamVO;
import org.jeecg.modules.KM.VO.KmDocEsParamVO;
import org.jeecg.modules.KM.common.rules.KMConstant;
import org.jeecg.modules.KM.entity.KmSearchRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class EsUtils {
    @Autowired
    private OpenSearchClient openSearchClient;
    @Autowired
    private KMConstant kmConstant;

/**
     * 获取使用 ik_smart 分析器分析后的搜索词列表。
     *
     * @param searchContent 需要分析的搜索内容
     * @return 分析后的搜索词列表
     */
    public List<String> getIkAnalyzeSearchTerms(String searchContent) {
        // 创建 AnalyzeRequest 实例
        AnalyzeRequest request = new AnalyzeRequest.Builder()
                .index(KMConstant.DocIndexAliasName)
                .analyzer("ik_smart")
                .text(searchContent)
                .build();

        List<String> result = new ArrayList<>();
        try {
            if (!searchContent.isEmpty()) {
                // 使用 OpenSearchClient 发送请求并获取响应
                AnalyzeResponse response = openSearchClient.indices().analyze(request);
                
                // 处理响应中的 tokens
                for (AnalyzeToken token : response.tokens()) {
                    result.add(token.token());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }
    public BoolQuery.Builder buildESQueryParams(List<KmDocEsFilterParamVO> filterParams){
        //最终条件
        BoolQuery.Builder boolQueryBuilder = QueryBuilders.bool();
        for (KmDocEsFilterParamVO filterParam : filterParams) {
            BoolQuery.Builder oneFilter = buildESQueryParam(filterParam);
	    // 将子查询的条件分别添加到主查询中
            boolQueryBuilder.must(Query.of(q -> q.bool(oneFilter.build())));
        }
        return boolQueryBuilder;
    }
public BoolQuery.Builder buildESQueryParam(KmDocEsFilterParamVO kmDocEsFilterParamVO) {

    // 创建 BoolQuery.Builder 实例
    BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();
    BoolQuery.Builder boolQueryBuilderDefault = new BoolQuery.Builder();

    // 1. 分类检索，用 filter
    if (kmDocEsFilterParamVO.getCategory() != null && !kmDocEsFilterParamVO.getCategory().isEmpty()) {
        List<String> categorys = kmDocEsFilterParamVO.getCategory();
	// 将 List<String> 转换为 List<FieldValue>
        List<FieldValue> fieldValues = categorys.stream()
        .map(FieldValue::of)
        .collect(Collectors.toList());
        Query termsQuery = new TermsQuery.Builder()
                .field("category")
                .terms(t -> t.value(fieldValues))
                .build()._toQuery();
        boolQueryBuilder.filter(termsQuery);
    }

    // 准备好标题、全文检索的条件
    Query titleMatchQuery = null;
    Query contentMatchQuery = null;
    if (oConvertUtils.isNotEmpty(kmDocEsFilterParamVO.getTitle())) {
        if (kmDocEsFilterParamVO.getPhraseMatchSearchFlag() != null && kmDocEsFilterParamVO.getPhraseMatchSearchFlag()) {
            titleMatchQuery = new MatchPhraseQuery.Builder()
                    .field("title")
                    .query(kmDocEsFilterParamVO.getTitle())
                    .slop(2)
                    .build()._toQuery();
        } else {
            FieldValue titleFieldValue = FieldValue.of(kmDocEsFilterParamVO.getTitle());
            titleMatchQuery = new MatchQuery.Builder()
                    .field("title")
                    .query(titleFieldValue)
                    .analyzer("ik_smart")
                    .boost(kmConstant.getTitleSearchBoost())
                    .build()._toQuery();
        }
    }
    if (oConvertUtils.isNotEmpty(kmDocEsFilterParamVO.getContent())) {
        if (kmDocEsFilterParamVO.getPhraseMatchSearchFlag() != null && kmDocEsFilterParamVO.getPhraseMatchSearchFlag()) {
            contentMatchQuery = new MatchPhraseQuery.Builder()
                    .field("content")
                    .query(kmDocEsFilterParamVO.getContent())
                    .slop(2)
                    .build()._toQuery();
        } else {
            FieldValue cFieldValue = FieldValue.of(kmDocEsFilterParamVO.getContent());
            contentMatchQuery = new MatchQuery.Builder()
                    .field("content")
                    .query(cFieldValue)
                    .analyzer("ik_smart")
                    .boost(kmConstant.getContentSearchBoost())
                    .build()._toQuery();
        }
    }

    // 2. 标题检索 高级用 must，快速用 should
    if (kmDocEsFilterParamVO.getTitle() != null && !kmDocEsFilterParamVO.getTitle().isEmpty()) {
        if (kmDocEsFilterParamVO.getAdvSearchFlag()) {
            boolQueryBuilder.must(titleMatchQuery);
        } else {
            boolQueryBuilderDefault.should(titleMatchQuery);
        }
    }

    // 3. 全文检索 高级用 must，快速用 should
    if (kmDocEsFilterParamVO.getContent() != null && !kmDocEsFilterParamVO.getContent().isEmpty()) {
        if (kmDocEsFilterParamVO.getAdvSearchFlag() != null && kmDocEsFilterParamVO.getAdvSearchFlag()) {
            boolQueryBuilder.must(contentMatchQuery);
        } else {
            boolQueryBuilderDefault.should(contentMatchQuery);
        }
    }

    // 4. 关键字检索 用 term 精确匹配; 高级用 must，快速用 should
    if (kmDocEsFilterParamVO.getKeywords() != null && !kmDocEsFilterParamVO.getKeywords().isEmpty()) {
        BoolQuery.Builder boolQueryBuilderKeywords = new BoolQuery.Builder();
        kmDocEsFilterParamVO.getKeywords().forEach(keyword -> {
	    FieldValue kFieldValue = FieldValue.of(keyword);
            boolQueryBuilderKeywords.should(new TermQuery.Builder()
                    .field("keywords")
                    .value(kFieldValue)
                    .boost(kmConstant.getKeywordSearchBoost())
                    .build()._toQuery());
        });
        if (kmDocEsFilterParamVO.getAdvSearchFlag() != null && kmDocEsFilterParamVO.getAdvSearchFlag()) {
            boolQueryBuilder.must(boolQueryBuilderKeywords.build()._toQuery());
        } else {
            boolQueryBuilderDefault.should(boolQueryBuilderKeywords.build()._toQuery());
        }
    }

    // 处理快速检索的合并条件：标题、关键字、全文
    if (kmDocEsFilterParamVO.getAdvSearchFlag() == null || !kmDocEsFilterParamVO.getAdvSearchFlag()) {
        boolQueryBuilder.must(boolQueryBuilderDefault.build()._toQuery());
    }

    // 5. 发布时间检索 用 filter
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    if (kmDocEsFilterParamVO.getCreateTimeEnd() != null) {
        Query rangeQueryEnd = new RangeQuery.Builder()
                .field("createTime")
                .lte(JsonData.of(format.format(kmDocEsFilterParamVO.getCreateTimeEnd())))
                .build()._toQuery();
        boolQueryBuilder.filter(rangeQueryEnd);
    }
    if (kmDocEsFilterParamVO.getCreateTimeStart() != null) {
        Query rangeQueryStart = new RangeQuery.Builder()
                .field("createTime")
                .gte(JsonData.of(format.format(kmDocEsFilterParamVO.getCreateTimeStart())))
                .build()._toQuery();
        boolQueryBuilder.filter(rangeQueryStart);
    }

    // 7. 标签检索（多选） 用 filter
    if (kmDocEsFilterParamVO.getBusinessTypes() != null && !kmDocEsFilterParamVO.getBusinessTypes().isEmpty()) {
        List<String> businessTypes = kmDocEsFilterParamVO.getBusinessTypes();
	// 将 List<String> 转换为 List<FieldValue>
        List<FieldValue> fieldValues = businessTypes.stream()
        .map(FieldValue::of)
        .collect(Collectors.toList());
        Query termsQuery = new TermsQuery.Builder()
                .field("businessTypes")
                .terms(t -> t.value(fieldValues))
                .build()._toQuery();
        boolQueryBuilder.filter(termsQuery);
    }

    // 9. 专题检索（多选，前缀模糊匹配） 用 filter
    if (kmDocEsFilterParamVO.getTopicCodes() != null && !kmDocEsFilterParamVO.getTopicCodes().isEmpty()) {
        BoolQuery.Builder boolQueryBuilderTopicCodes = new BoolQuery.Builder();
        for (String topicCode : kmDocEsFilterParamVO.getTopicCodes()) {
            boolQueryBuilderTopicCodes.should(new PrefixQuery.Builder()
                    .field("topicCodes")
                    .value(topicCode)
                    .build()._toQuery());
        }
        Query boolQueryTopicCodes = boolQueryBuilderTopicCodes.build()._toQuery();
        boolQueryBuilder.filter(boolQueryTopicCodes);
    }

    // 返回 BoolQuery.Builder 而不是构建好的 BoolQuery
    return boolQueryBuilder;
}
}
