package lab.commerceflow.storefront.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lab.commerceflow.storefront.domain.ScheduledTaskPolicyView;
import lab.commerceflow.storefront.domain.ScheduledTaskPolicyEventView;
import lab.commerceflow.storefront.domain.ScheduledTaskEventView;
import lab.commerceflow.storefront.domain.ScheduledTaskView;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ScheduledTaskMapper {
  @Select("SELECT * FROM scheduled_task_policies WHERE task_type=#{taskType}")
  Optional<ScheduledTaskPolicyView> policy(String taskType);

  @Select("SELECT * FROM scheduled_task_policies ORDER BY task_type")
  List<ScheduledTaskPolicyView> policies();

  @Insert("""
      INSERT INTO scheduled_task_policies(
        task_type,enabled,primary_trigger,reconciliation_enabled,grace_period_seconds,
        claim_lease_seconds,max_attempts,retry_delay_seconds,config_version,updated_by)
      VALUES(#{taskType},#{enabled},#{primaryTrigger},#{reconciliationEnabled},#{gracePeriodSeconds},
        #{claimLeaseSeconds},#{maxAttempts},#{retryDelaySeconds},1,#{updatedBy})
      ON DUPLICATE KEY UPDATE
        enabled=VALUES(enabled),primary_trigger=VALUES(primary_trigger),
        reconciliation_enabled=VALUES(reconciliation_enabled),grace_period_seconds=VALUES(grace_period_seconds),
        claim_lease_seconds=VALUES(claim_lease_seconds),max_attempts=VALUES(max_attempts),
        retry_delay_seconds=VALUES(retry_delay_seconds),config_version=config_version+1,
        updated_by=VALUES(updated_by),updated_at=CURRENT_TIMESTAMP(6)
      """)
  void upsertPolicy(@Param("taskType") String taskType, @Param("enabled") boolean enabled,
                    @Param("primaryTrigger") String primaryTrigger,
                    @Param("reconciliationEnabled") boolean reconciliationEnabled,
                    @Param("gracePeriodSeconds") long gracePeriodSeconds,
                    @Param("claimLeaseSeconds") long claimLeaseSeconds,
                    @Param("maxAttempts") int maxAttempts,
                    @Param("retryDelaySeconds") long retryDelaySeconds,
                    @Param("updatedBy") String updatedBy);

  @Delete("DELETE FROM scheduled_task_policies WHERE task_type=#{taskType}")
  int deletePolicy(String taskType);

  @Insert("""
      INSERT INTO scheduled_task_policy_events(
        task_type,action,enabled,primary_trigger,reconciliation_enabled,grace_period_seconds,
        claim_lease_seconds,max_attempts,retry_delay_seconds,config_version,operated_by)
      VALUES(#{taskType},#{action},#{enabled},#{primaryTrigger},#{reconciliationEnabled},
        #{gracePeriodSeconds},#{claimLeaseSeconds},#{maxAttempts},#{retryDelaySeconds},#{configVersion},#{operatedBy})
      """)
  void insertPolicyEvent(@Param("taskType") String taskType, @Param("action") String action,
                         @Param("enabled") boolean enabled, @Param("primaryTrigger") String primaryTrigger,
                         @Param("reconciliationEnabled") boolean reconciliationEnabled,
                         @Param("gracePeriodSeconds") long gracePeriodSeconds,
                         @Param("claimLeaseSeconds") long claimLeaseSeconds,
                         @Param("maxAttempts") int maxAttempts,
                         @Param("retryDelaySeconds") long retryDelaySeconds,
                         @Param("configVersion") long configVersion,
                         @Param("operatedBy") String operatedBy);

  @Select("SELECT * FROM scheduled_task_policy_events WHERE task_type=#{taskType} ORDER BY id DESC LIMIT 200")
  List<ScheduledTaskPolicyEventView> policyEvents(String taskType);

  @Insert("""
      INSERT INTO scheduled_tasks(
        task_key,task_type,aggregate_type,aggregate_id,payload_json,status,due_at,
        primary_trigger_snapshot,reconciliation_enabled_snapshot,grace_period_seconds_snapshot,
        claim_lease_seconds_snapshot,max_attempts,retry_delay_seconds_snapshot,policy_version_snapshot)
      VALUES(#{taskKey},#{taskType},#{aggregateType},#{aggregateId},#{payloadJson},'SCHEDULED',#{dueAt},
        #{primaryTriggerSnapshot},#{reconciliationEnabledSnapshot},#{gracePeriodSecondsSnapshot},
        #{claimLeaseSecondsSnapshot},#{maxAttempts},#{retryDelaySecondsSnapshot},#{policyVersionSnapshot})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insert(ScheduledTaskView task);

  @Select("SELECT * FROM scheduled_tasks WHERE task_type=#{taskType} AND task_key=#{taskKey}")
  Optional<ScheduledTaskView> byBusinessKey(@Param("taskType") String taskType,
                                            @Param("taskKey") String taskKey);

  @Select("SELECT * FROM scheduled_tasks WHERE id=#{id}")
  Optional<ScheduledTaskView> byId(Long id);

  @Select("""
      SELECT id FROM scheduled_tasks
      WHERE primary_trigger_snapshot='DATABASE'
        AND status IN ('SCHEDULED','FAILED')
        AND due_at<=#{now} AND attempts<max_attempts
      ORDER BY due_at,id LIMIT #{limit}
      """)
  List<Long> databaseCandidates(@Param("now") Instant now, @Param("limit") int limit);

  @Select("""
      SELECT id FROM scheduled_tasks
      WHERE reconciliation_enabled_snapshot=TRUE
        AND status IN ('SCHEDULED','FAILED')
        AND due_at<=#{now} AND attempts<max_attempts
        AND TIMESTAMPADD(SECOND,grace_period_seconds_snapshot,due_at)<=#{now}
      ORDER BY due_at,id LIMIT #{limit}
      """)
  List<Long> reconciliationCandidates(@Param("now") Instant now,
                                      @Param("limit") int limit);

  @Update("""
      UPDATE scheduled_tasks
      SET status='PROCESSING',attempts=attempts+1,lease_until=#{leaseUntil},claimed_by=#{owner},
          last_trigger_source=#{source},last_error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP(6)
      WHERE id=#{id} AND status IN ('SCHEDULED','FAILED') AND due_at<=#{now}
        AND attempts<max_attempts AND (lease_until IS NULL OR lease_until<#{now})
      """)
  int claim(@Param("id") Long id, @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil, @Param("owner") String owner,
            @Param("source") String source);

  @Update("""
      UPDATE scheduled_tasks SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6),lease_until=NULL,
        claimed_by=NULL,last_error=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP(6)
      WHERE id=#{id} AND status='PROCESSING' AND claimed_by=#{owner}
      """)
  int succeed(@Param("id") Long id, @Param("owner") String owner);

  @Update("""
      UPDATE scheduled_tasks SET status='FAILED',due_at=#{retryAt},lease_until=NULL,claimed_by=NULL,
        last_error=#{error},version=version+1,updated_at=CURRENT_TIMESTAMP(6)
      WHERE id=#{id} AND status='PROCESSING' AND claimed_by=#{owner}
      """)
  int fail(@Param("id") Long id, @Param("owner") String owner,
           @Param("retryAt") Instant retryAt, @Param("error") String error);

  @Select("SELECT id FROM scheduled_tasks WHERE status='PROCESSING' AND lease_until<#{now} ORDER BY lease_until,id LIMIT #{limit}")
  List<Long> expiredLeaseIds(@Param("now") Instant now, @Param("limit") int limit);

  @Update("""
      UPDATE scheduled_tasks SET status='FAILED',lease_until=NULL,claimed_by=NULL,
        last_error='Execution lease expired',version=version+1,updated_at=CURRENT_TIMESTAMP(6)
      WHERE id=#{id} AND status='PROCESSING' AND lease_until<#{now}
      """)
  int recoverExpiredLease(@Param("id") Long id, @Param("now") Instant now);

  @Update("""
      UPDATE scheduled_tasks SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP(6),lease_until=NULL,
        claimed_by=NULL,last_error=#{reason},version=version+1,updated_at=CURRENT_TIMESTAMP(6)
      WHERE id=#{id} AND status IN ('CREATED','SCHEDULED','FAILED')
      """)
  int cancel(@Param("id") Long id, @Param("reason") String reason);

  @Update("""
      UPDATE scheduled_tasks SET status='SCHEDULED',due_at=CURRENT_TIMESTAMP(6),lease_until=NULL,
        claimed_by=NULL,last_error=NULL,attempts=0,version=version+1,updated_at=CURRENT_TIMESTAMP(6)
      WHERE id=#{id} AND status='FAILED'
      """)
  int retry(Long id);

  @Select("""
      <script>
      SELECT * FROM scheduled_tasks
      <where>
        <if test='taskType != null and taskType != ""'>task_type=#{taskType}</if>
        <if test='status != null and status != ""'>AND status=#{status}</if>
      </where>
      ORDER BY id DESC LIMIT #{limit}
      </script>
      """)
  List<ScheduledTaskView> list(@Param("taskType") String taskType,
                               @Param("status") String status,
                               @Param("limit") int limit);

  @Insert("""
      INSERT INTO scheduled_task_events(task_id,event_type,trigger_source,from_status,to_status,detail)
      VALUES(#{taskId},#{eventType},#{source},#{fromStatus},#{toStatus},#{detail})
      """)
  void insertEvent(@Param("taskId") Long taskId, @Param("eventType") String eventType,
                   @Param("source") String source, @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus, @Param("detail") String detail);

  @Select("SELECT * FROM scheduled_task_events WHERE task_id=#{taskId} ORDER BY id")
  List<ScheduledTaskEventView> taskEvents(Long taskId);
}
