package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.LendingEvents.LoanDefaulted;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-of-day loan book processing, as every lender runs it:
 * 1. installments past their due date become OVERDUE,
 * 2. overdue installments accrue a daily penalty, capped so penalties can never exceed the
 *    installment's principal (an "in duplum"-style cap),
 * 3. loans past the default threshold move to DEFAULTED (and an event goes out).
 *
 * <p>Run-once-per-day is enforced by a row in lending_job_run, so it is safe with several app
 * instances and with restarts, and it can be re-run for a past date to backfill a missed day.
 */
@Component
class LoanDailyJobs {

    private static final Logger log = LoggerFactory.getLogger(LoanDailyJobs.class);
    static final String JOB = "loan-eod";

    private final JdbcClient jdbc;
    private final LoanRepository loans;
    private final LendingProperties props;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    LoanDailyJobs(JdbcClient jdbc, LoanRepository loans, LendingProperties props, ApplicationEventPublisher events,
                  TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.props = props;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 0 * * *", zone = "Africa/Nairobi")
    void nightly() {
        run(LocalDate.now(clock));
    }

    record Result(LocalDate businessDate, boolean ran, int newlyOverdue, int penalised, int defaulted) {}

    Result run(LocalDate today) {
        int claimed = jdbc.sql("INSERT INTO lending_job_run (job_name, run_date) VALUES (:j, :d) ON CONFLICT DO NOTHING")
                .param("j", JOB).param("d", Date.valueOf(today)).update();
        if (claimed == 0) {
            log.info("{} already ran for {}", JOB, today);
            return new Result(today, false, 0, 0, 0);
        }

        Integer[] counts = tx.execute(s -> {
            int overdue = jdbc.sql("""
                            UPDATE lending_installment SET status = 'OVERDUE'
                            WHERE status = 'DUE' AND due_date < :today
                            """)
                    .param("today", Date.valueOf(today)).update();

            int penalised = jdbc.sql("""
                            UPDATE lending_installment i
                            SET penalty_due = LEAST(
                                    i.penalty_due + (i.principal_due - i.principal_paid) * p.penalty_bps_per_day / 10000,
                                    i.principal_due)
                            FROM lending_loan l
                            JOIN lending_product p ON p.code = l.product_code
                            WHERE i.loan_id = l.id
                              AND l.status = 'ACTIVE'
                              AND i.status = 'OVERDUE'
                              AND i.principal_due > i.principal_paid
                            """)
                    .update();
            return new Integer[] {overdue, penalised};
        });

        List<UUID> toDefault = jdbc.sql("""
                        SELECT DISTINCT l.id FROM lending_loan l
                        JOIN lending_installment i ON i.loan_id = l.id
                        WHERE l.status = 'ACTIVE' AND i.status = 'OVERDUE' AND i.due_date < :cutoff
                        """)
                .param("cutoff", Date.valueOf(today.minusDays(props.defaultAfterDays())))
                .query(UUID.class).list();

        for (UUID id : toDefault) {
            tx.executeWithoutResult(s -> {
                var loan = loans.findForUpdate(id).orElseThrow();
                if (loan.status() != Loan.Status.ACTIVE) {
                    return;
                }
                loan.markDefaulted();
                events.publishEvent(new LoanDefaulted(loan.id(), loan.borrowerId(), loan.agentId(),
                        loan.outstanding(), Instant.now(clock)));
            });
        }

        var result = new Result(today, true, counts[0], counts[1], toDefault.size());
        log.info("{} finished: {}", JOB, result);
        return result;
    }
}
