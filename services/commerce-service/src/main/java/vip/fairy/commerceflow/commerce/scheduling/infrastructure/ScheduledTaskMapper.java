package vip.fairy.commerceflow.commerce.scheduling.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskPolicyView;
import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskPolicyEventView;
import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskEventView;
import vip.fairy.commerceflow.commerce.scheduling.domain.ScheduledTaskView;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

public interface ScheduledTaskMapper {

  Optional<ScheduledTaskPolicyView> policy(String taskType);


  List<ScheduledTaskPolicyView> policies();


  void upsertPolicy(@Param("taskType") String taskType, @Param("enabled") boolean enabled,
                    @Param("primaryTrigger") String primaryTrigger,
                    @Param("reconciliationEnabled") boolean reconciliationEnabled,
                    @Param("gracePeriodSeconds") long gracePeriodSeconds,
                    @Param("claimLeaseSeconds") long claimLeaseSeconds,
                    @Param("maxAttempts") int maxAttempts,
                    @Param("retryDelaySeconds") long retryDelaySeconds,
                    @Param("updatedBy") String updatedBy);


  int deletePolicy(String taskType);


  void insertPolicyEvent(@Param("taskType") String taskType, @Param("action") String action,
                         @Param("enabled") boolean enabled, @Param("primaryTrigger") String primaryTrigger,
                         @Param("reconciliationEnabled") boolean reconciliationEnabled,
                         @Param("gracePeriodSeconds") long gracePeriodSeconds,
                         @Param("claimLeaseSeconds") long claimLeaseSeconds,
                         @Param("maxAttempts") int maxAttempts,
                         @Param("retryDelaySeconds") long retryDelaySeconds,
                         @Param("configVersion") long configVersion,
                         @Param("operatedBy") String operatedBy);


  List<ScheduledTaskPolicyEventView> policyEvents(String taskType);


  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(ScheduledTaskView task);


  Optional<ScheduledTaskView> byBusinessKey(@Param("taskType") String taskType,
                                            @Param("taskKey") String taskKey);


  Optional<ScheduledTaskView> byId(Long id);


  List<Long> databaseCandidates(@Param("now") Instant now, @Param("limit") int limit);


  List<Long> triggerPublishCandidates(@Param("strategy") String strategy,
                                      @Param("limit") int limit);


  int markTriggerPublished(@Param("id") Long id, @Param("strategy") String strategy,
                           @Param("publishedAt") Instant publishedAt);


  int markTriggerPublishFailure(@Param("id") Long id, @Param("error") String error);


  int clearTriggerPublished(@Param("id") Long id, @Param("strategy") String strategy,
                            @Param("reason") String reason);


  List<Long> reconciliationCandidates(@Param("now") Instant now,
                                      @Param("limit") int limit);


  int claim(@Param("id") Long id, @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil, @Param("owner") String owner,
            @Param("source") String source);


  int succeed(@Param("id") Long id, @Param("owner") String owner);


  int fail(@Param("id") Long id, @Param("owner") String owner,
           @Param("retryAt") Instant retryAt, @Param("error") String error);


  List<Long> expiredLeaseIds(@Param("now") Instant now, @Param("limit") int limit);


  int recoverExpiredLease(@Param("id") Long id, @Param("now") Instant now);


  int cancel(@Param("id") Long id, @Param("reason") String reason);


  int retry(Long id);


  List<ScheduledTaskView> list(@Param("taskType") String taskType,
                               @Param("status") String status,
                               @Param("limit") int limit);


  void insertEvent(@Param("taskId") Long taskId, @Param("eventType") String eventType,
                   @Param("source") String source, @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus, @Param("detail") String detail);


  List<ScheduledTaskEventView> taskEvents(Long taskId);
}
