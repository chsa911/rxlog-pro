package com.acme.enrichment.repo;

import com.acme.enrichment.model.EnrichmentJob;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class EnrichmentJobRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public EnrichmentJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EnrichmentJob> claimBatch(int limit, String workerId) {
        String sql = """
      with cte as (
        select id
        from book_enrichment_job
        where
          (status='queued' and next_run_at <= now())
          or
          (status='processing' and locked_at < now() - interval '5 minutes')
        order by created_at
        limit :limit
        for update skip locked
      )
      update book_enrichment_job j
      set status='processing',
          locked_at=now(),
          locked_by=:workerId,
          attempts=attempts+1,
          updated_at=now()
      from cte
      where j.id = cte.id
      returning j.id as job_id, j.book_id::text as book_id, j.attempts
    """;

        var params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("workerId", workerId);

        return jdbc.query(sql, params, (rs, rowNum) ->
                new EnrichmentJob(
                        rs.getLong("job_id"),
                        rs.getString("book_id"),
                        rs.getInt("attempts")
                )
        );
    }

    public void markDone(long jobId) {
        jdbc.update(
                """
                update book_enrichment_job
                set status='done',
                    updated_at=now(),
                    last_error=null,
                    locked_at=null,
                    locked_by=null
                where id=:id
                """,
                new MapSqlParameterSource("id", jobId)
        );
    }

    public void markErrorFinal(long jobId, String err) {
        jdbc.update(
                "update book_enrichment_job set status='error', last_error=:err, updated_at=now() where id=:id",
                new MapSqlParameterSource()
                        .addValue("id", jobId)
                        .addValue("err", truncate(err))
        );
    }

    public void markRetry(long jobId, String err, int delaySeconds) {
        jdbc.update(
                """
                update book_enrichment_job
                set status='queued',
                    last_error=:err,
                    updated_at=now(),
                    next_run_at = now() + make_interval(secs => :delaySecs),
                    locked_at=null,
                    locked_by=null
                where id=:id
                """,
                new MapSqlParameterSource()
                        .addValue("id", jobId)
                        .addValue("err", truncate(err))
                        .addValue("delaySecs", delaySeconds)
        );
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}