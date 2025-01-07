package org.jeecg.modules.KM.service.impl;

import cn.hutool.core.date.DatePattern;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
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

import org.opensearch.client.json.JsonData;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.DateUtils;
import org.jeecg.common.util.UUIDGenerator;
import org.jeecg.modules.KM.VO.KmDocEsVO;
import org.jeecg.modules.KM.VO.KmDocVisitRecordEsVO;
import org.jeecg.modules.KM.common.enums.DocVisitTypeEnum;
import org.jeecg.modules.KM.common.rules.KMConstant;
import org.jeecg.modules.KM.common.utils.EsUtils;
import org.jeecg.modules.KM.common.utils.KMDateUtils;
import org.jeecg.modules.KM.common.utils.KMRedisUtils;
import org.jeecg.modules.KM.common.utils.StringUtils;
import org.jeecg.modules.KM.entity.KmDocVisitRecord;
import org.jeecg.modules.KM.mapper.KmDocVisitRecordMapper;
import org.jeecg.modules.KM.service.IKmDocVisitRecordService;
import org.jeecg.modules.KM.service.IThreadPoolExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.Date;
import java.util.Date;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KmDocVisitRecordServiceImpl extends ServiceImpl<KmDocVisitRecordMapper, KmDocVisitRecord> implements IKmDocVisitRecordService {

    @Autowired
    private IThreadPoolExecutorService executorService;
    @Autowired
    private EsUtils esUtils;
    @Autowired
    private OpenSearchClient openSearchClient;
    @Autowired
    private KMRedisUtils kMRedisUtils;

    @Override
    public void logVisit(String docId,String ip,Integer visitType) {
        logVisit(docId,ip,visitType,"");
    }

    @Override
    public void logVisit(String docId,String ip,Integer visitType,String keyword) {
        LoginUser sysUser = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        //String userId = "1";
        if(sysUser != null) {
            String userId = sysUser.getUsername();
            logVisit(docId, ip, visitType, keyword, userId);
        }
    }

    @Override
    public void logVisit(String docId,String ip,Integer visitType,String keyword,String userId){
        KmDocVisitRecord kmDocVisitRecord = new KmDocVisitRecord();
        kmDocVisitRecord.setId(UUIDGenerator.generate());
        kmDocVisitRecord.setDocId(docId);
        kmDocVisitRecord.setCreateBy(userId);
        kmDocVisitRecord.setCreateTime(DateUtils.getDate());
        kmDocVisitRecord.setVisitType(visitType);
        kmDocVisitRecord.setSourceIp(ip);
        kmDocVisitRecord.setKeywords(keyword);

        //通过es获取分词结果
        List<String> paramKeywordList = esUtils.getIkAnalyzeSearchTerms(keyword);
        String keywordsMax = StringUtils.concatListToString(paramKeywordList);
        kmDocVisitRecord.setKeywordsMax(keywordsMax);
        executorService.execute(()->super.save(kmDocVisitRecord));

        //下载、预览:保存最近访问的个人文档记录与ES日志
        if(visitType == DocVisitTypeEnum.View.getCode()
                || visitType == DocVisitTypeEnum.Download.getCode()) {
            //入库ES
            executorService.execute(()->saveToEs(convertToEsVo(kmDocVisitRecord)));

            executorService.execute(() -> kMRedisUtils.logPersonalDocHistory(userId, docId));
        }
    }


    private KmDocVisitRecordEsVO convertToEsVo(KmDocVisitRecord kmDocVisitRecord){
        KmDocVisitRecordEsVO kmDocVisitRecordEsVO = new KmDocVisitRecordEsVO();
        kmDocVisitRecordEsVO.setCreateBy(kmDocVisitRecord.getCreateBy());
        kmDocVisitRecordEsVO.setCreateTime(kmDocVisitRecord.getCreateTime());
        kmDocVisitRecordEsVO.setDocId(kmDocVisitRecord.getDocId());
        kmDocVisitRecordEsVO.setKeywords(kmDocVisitRecord.getKeywords());
        if(kmDocVisitRecord.getKeywordsMax() != null&& kmDocVisitRecord.getKeywordsMax().length()>0)
            kmDocVisitRecordEsVO.setKeywordsMax(kmDocVisitRecord.getKeywordsMax().split(","));
        kmDocVisitRecordEsVO.setSourceIp(kmDocVisitRecord.getSourceIp());
        kmDocVisitRecordEsVO.setVisitType(kmDocVisitRecord.getVisitType());
        return  kmDocVisitRecordEsVO;
    }


    private void saveToEs(KmDocVisitRecordEsVO kmDocVisitRecordEsVO) {
        try {
            //插入数据，index不存在则自动根据匹配到的template创建。index没必要每天创建一个，如果是为了灵活管理，最低建议每月一个 yyyyMM。
            String indexSuffix = KMDateUtils.formatDateyyyyMM(DateUtils.getDate());
            IndexRequest.Builder<KmDocVisitRecordEsVO> indexRequest = new IndexRequest.Builder<KmDocVisitRecordEsVO>()
		    .index(KMConstant.DocVisitIndexName + "_" + indexSuffix);
            indexRequest.timeout(Time.of(t -> t.time(String.format("%sm",KMConstant.SaveTimeOutMinutes))));
            indexRequest.refresh(Refresh.WaitFor);
	        indexRequest.document(kmDocVisitRecordEsVO);
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


    @Deprecated
    @Override
    public List<String> recentlyVisitedDocs(String createBy) throws IOException {
        List<String> result = new ArrayList<>();
        // I GIVE UP.

        return result;
    }

}
