package org.jeecg.modules.KM.service.impl;

import cn.hutool.core.date.DatePattern;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.transport.TransportOptions;
import org.opensearch.client.opensearch._types.Time;
import org.opensearch.client.json.JsonData;
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
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;

import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.StringTermsAggregate;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.aggregations.TermsAggregation;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.system.vo.SysCategoryModel;
import org.jeecg.common.util.DateUtils;
import org.jeecg.modules.KM.VO.KmSearchRecordEsVO;
import org.jeecg.modules.KM.common.rules.KMConstant;
import org.jeecg.modules.KM.common.utils.EsUtils;
import org.jeecg.modules.KM.common.utils.KMDateUtils;
import org.jeecg.modules.KM.common.utils.StringUtils;
import org.jeecg.modules.KM.entity.KmSearchRecord;
import org.jeecg.modules.KM.mapper.KmSearchRecordMapper;
import org.jeecg.modules.KM.service.IKmSearchRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class KmSearchRecordServiceImpl extends ServiceImpl<KmSearchRecordMapper, KmSearchRecord> implements IKmSearchRecordService {

    @Autowired
    private EsUtils esUtils;
    @Autowired
    private OpenSearchClient openSearchClient;
    @Autowired
    private ISysBaseAPI sysBaseAPI;

    @Override
    public void logSearch(String keyword,String title,String content,String topicCode,String ip){
        KmSearchRecord kmSearchRecord = new KmSearchRecord();
        LoginUser sysUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        if(sysUser == null)
            return ;

        String userId = sysUser.getUsername();
        kmSearchRecord.setCreateBy(userId);

        //todo id generation
        kmSearchRecord.setSourceIp(ip);
        kmSearchRecord.setKeywords(keyword);
        kmSearchRecord.setTitle(title);
        kmSearchRecord.setContent(content);
        kmSearchRecord.setTopicCodes(topicCode);
        kmSearchRecord.setCreateBy(userId);
        kmSearchRecord.setCreateTime(DateUtils.getDate());

        String keywordString = "";
        if(content != null) keywordString += content;
        if(title != null) keywordString = keywordString + "," + title;
        List<String> paramKeywordList = esUtils.getIkAnalyzeSearchTerms(keywordString);
        //keyword不做分词
        if(keyword != null)
            paramKeywordList.add(keyword);

        kmSearchRecord.setKeywordsMax(StringUtils.concatListToString(paramKeywordList));

        super.save(kmSearchRecord);

        //入库ES
        saveToEs(convertToEsVo(kmSearchRecord));
    }

    @Override
    public List<String> hotKeywordReport() throws IOException {
        List<String> result = new ArrayList<>();
    
        // 构建聚合
        TermsAggregation aggregation = new TermsAggregation.Builder()
                .field("keywordsMax")
                .size(10)
                .build();
    
        // 构建搜索请求
	SearchRequest searchRequest = null;
        SearchResponse<Object> searchResponse = null;
	try{
           searchRequest = new SearchRequest.Builder()
                .index(KMConstant.KMSearchRecordIndexAliasName)
                .aggregations("keyword", new Aggregation.Builder().terms(aggregation).build())
                .timeout(String.format("%ss",KMConstant.SearchTimeOutSeconds))
                .build();

        // 执行搜索
           searchResponse = openSearchClient.search(searchRequest, Object.class);
        }catch(Exception e){
	   {
		log.error("exception when  search the record result");
	   }
	   return null;	
	}
        // 执行搜索
        searchResponse = openSearchClient.search(searchRequest, Object.class);
    
        // 检查搜索结果
        if (searchResponse.hits().total().value() <= 0) {
            return null;
        } else {
            // 获取聚合结果
            StringTermsAggregate terms = searchResponse.aggregations().get("keyword").sterms();
            List<StringTermsBucket> buckets = terms.buckets().array();
    
            // 遍历聚合桶
            for (StringTermsBucket bucket : buckets) {
                String term = bucket.key();
                result.add(term);
            }
        }
        return result;
    }

    @Override
    public List<SysCategoryModel> retriveHotTopic() throws IOException {
        List<SysCategoryModel> result = new ArrayList<>();
        try{ 
        // 构建聚合
        TermsAggregation aggregation = new TermsAggregation.Builder()
                .field("topicCodes")
                .size(10)
                .build();
    
        // 构建搜索请求
        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(KMConstant.KMSearchRecordIndexAliasName)
                .aggregations("topicCode", new Aggregation.Builder().terms(aggregation).build())
                .timeout(String.format("%ss",KMConstant.SearchTimeOutSeconds))
                .build();
    
        // 执行搜索
        SearchResponse<Object> searchResponse = openSearchClient.search(searchRequest, Object.class);
    
        // 检查搜索结果
        if (searchResponse.hits().total().value() <= 0) {
            return null;
        } else {
            // 获取聚合结果
            StringTermsAggregate terms = searchResponse.aggregations().get("topicCode").sterms();
            List<StringTermsBucket> buckets = terms.buckets().array();
    
            // 遍历聚合桶
            for (StringTermsBucket bucket : buckets) {
                String term = bucket.key();
                SysCategoryModel sysCategoryModel = sysBaseAPI.queryCategoryByCode(term);
                if (sysCategoryModel != null) {
                    result.add(sysCategoryModel);
                }
            }
        }
	}catch(Exception e){
	  log.warn("exception in search log, maybe it's the first run, you can ignore it.");
	}
        return result;
    }

    @Override
    public List<String> hotTopicReport()  {
        List<String> result = new ArrayList<>();
        try {
            List<SysCategoryModel> sysCategoryModels = retriveHotTopic();
            if(sysCategoryModels != null && !sysCategoryModels.isEmpty()) {
                sysCategoryModels
                        .stream()
                        .forEach(
                                e -> {
                                    result.add(e.getName());
                                });
                return result;
            }
        }
        catch (IOException e){
            //e.printStackTrace();
	    log.warn("Not found all hotTopics, but this does not matter, maybe its the first run.");
            return result;
        }
        return result;
    }

    private KmSearchRecordEsVO convertToEsVo(KmSearchRecord kmSearchRecord){
        KmSearchRecordEsVO kmSearchRecordEsVO = new KmSearchRecordEsVO();
        kmSearchRecordEsVO.setContent(kmSearchRecord.getContent());
        kmSearchRecordEsVO.setCreateBy(kmSearchRecord.getCreateBy());
        kmSearchRecordEsVO.setCreateTime(kmSearchRecord.getCreateTime());
        kmSearchRecordEsVO.setKeywords(kmSearchRecord.getKeywords());
        if(kmSearchRecord.getKeywordsMax() !=null && kmSearchRecord.getKeywordsMax().length()>0)
            kmSearchRecordEsVO.setKeywordsMax(kmSearchRecord.getKeywordsMax().split(","));
        kmSearchRecordEsVO.setTitle(kmSearchRecord.getTitle());
        if(kmSearchRecord.getTopicCodes() != null && kmSearchRecord.getTopicCodes().length()>0)
            kmSearchRecordEsVO.setTopicCodes(kmSearchRecord.getTopicCodes().split(","));
        kmSearchRecordEsVO.setSourceIp(kmSearchRecord.getSourceIp());
        return  kmSearchRecordEsVO;
    }

    //heavily copy from visit record
    private void saveToEs(KmSearchRecordEsVO kmDocRecordEsVO) {
        try {
            //插入数据，index不存在则自动根据匹配到的template创建。index没必要每天创建一个，如果是为了灵活管理，最低建议每月一个 yyyyMM。
            String indexSuffix = KMDateUtils.formatDateyyyyMM(DateUtils.getDate());
            IndexRequest.Builder<KmSearchRecordEsVO> indexRequest = new IndexRequest.Builder<KmSearchRecordEsVO>()
		    .index(KMConstant.DocVisitIndexName + "_" + indexSuffix);
            indexRequest.timeout(Time.of(t -> t.time(String.format("%sm",KMConstant.SaveTimeOutMinutes))));
            indexRequest.refresh(Refresh.WaitFor);
	        indexRequest.document(kmDocRecordEsVO);
            IndexResponse response = openSearchClient.index(indexRequest.build());
            if (!"created".equalsIgnoreCase(response.result().toString())) {
                log.error("入库ES发生错误，返回码:" + response.result().toString() );
            }
            else
                log.debug("访问记录入库ES成功");
        }
        catch (Exception e){
            log.error("入库ES发生错误" ,e );
        }
    }

}
