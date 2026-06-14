package com.iyzipay.utils;

import com.iyzipay.model.PaymentDetail;
import com.iyzipay.model.ReportingPaymentDetail;
import com.iyzipay.model.TransactionDetail;
import com.iyzipay.request.ReportingPaymentDetailRequest;
import com.iyzipay.request.ReportingPaymentTransactionRequest;
import com.iyzipay.Options;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DstCompensationScheduler {

    private final TimeZone reportingTimeZone;
    private final Options options;
    private final Map<String, CompensationTask> taskRegistry;
    private final ExecutorService executorService;
    private final long defaultTimeoutSeconds;
    private final ReportingBoundaryProcessor boundaryProcessor;

    public DstCompensationScheduler(Options options) {
        this(options, TimeZone.getTimeZone(TimeBoundaryUtils.TURKEY_TIMEZONE_ID));
    }

    public DstCompensationScheduler(Options options, TimeZone reportingTimeZone) {
        this.options = options;
        this.reportingTimeZone = reportingTimeZone;
        this.taskRegistry = new ConcurrentHashMap<String, CompensationTask>();
        this.executorService = Executors.newFixedThreadPool(4);
        this.defaultTimeoutSeconds = 300;
        this.boundaryProcessor = new ReportingBoundaryProcessor(reportingTimeZone);
    }

    public Date[] findDstTransitionDatesInRange(Date rangeStart, Date rangeEnd) {
        List<Date> transitionDates = new ArrayList<Date>();
        Calendar cal = Calendar.getInstance(reportingTimeZone);
        cal.setTime(TimeBoundaryUtils.getStartOfDay(rangeStart, reportingTimeZone));
        Date endDay = TimeBoundaryUtils.getStartOfDay(rangeEnd, reportingTimeZone);

        while (!cal.getTime().after(endDay)) {
            Date current = cal.getTime();
            if (TimeBoundaryUtils.isDaylightSavingTransitionDay(current, reportingTimeZone)) {
                transitionDates.add(current);
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        return transitionDates.toArray(new Date[0]);
    }

    public Date[] findAffectedDatesForCompensation(Date targetDate) {
        List<Date> affected = new ArrayList<Date>();
        Calendar cal = Calendar.getInstance(reportingTimeZone);
        cal.setTime(TimeBoundaryUtils.getStartOfDay(targetDate, reportingTimeZone));
        cal.add(Calendar.DAY_OF_MONTH, -2);

        for (int i = 0; i < 5; i++) {
            Date checkDate = cal.getTime();
            if (TimeBoundaryUtils.isDaylightSavingTransitionDay(checkDate, reportingTimeZone)) {
                affected.add(TimeBoundaryUtils.getStartOfDay(checkDate, reportingTimeZone));
                Calendar neighborCal = Calendar.getInstance(reportingTimeZone);
                neighborCal.setTime(checkDate);
                neighborCal.add(Calendar.DAY_OF_MONTH, -1);
                affected.add(0, TimeBoundaryUtils.getStartOfDay(neighborCal.getTime(), reportingTimeZone));
                neighborCal.add(Calendar.DAY_OF_MONTH, 2);
                affected.add(TimeBoundaryUtils.getStartOfDay(neighborCal.getTime(), reportingTimeZone));
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return affected.toArray(new Date[0]);
    }

    public CompensationResult scheduleCompensation(Date businessDate) {
        Date[] affectedDates = findAffectedDatesForCompensation(businessDate);
        return executeCompensation(affectedDates);
    }

    public CompensationResult scheduleCompensationForRange(Date rangeStart, Date rangeEnd) {
        Date[] transitionDates = findDstTransitionDatesInRange(rangeStart, rangeEnd);
        List<Date> allAffected = new ArrayList<Date>();
        for (Date td : transitionDates) {
            for (Date d : findAffectedDatesForCompensation(td)) {
                if (!allAffected.contains(d)) {
                    allAffected.add(d);
                }
            }
        }
        Collections.sort(allAffected);
        return executeCompensation(allAffected.toArray(new Date[0]));
    }

    private CompensationResult executeCompensation(Date[] targetDates) {
        CompensationResult result = new CompensationResult(targetDates.length);
        result.setTargetDates(targetDates);

        if (targetDates == null || targetDates.length == 0) {
            result.setStatus(CompensationStatus.NO_DATES_AFFECTED);
            result.setCompletedAt(new Date());
            return result;
        }

        Map<Date, Future<CompensationTaskResult>> futures = new LinkedHashMap<Date, Future<CompensationTaskResult>>();

        for (final Date date : targetDates) {
            final String taskId = generateTaskId(date);
            final CompensationTask task = new CompensationTask(taskId, date, CompensationStatus.PENDING);
            taskRegistry.put(taskId, task);

            Callable<CompensationTaskResult> callable = new Callable<CompensationTaskResult>() {
                @Override
                public CompensationTaskResult call() {
                    return executeSingleCompensation(date, taskId);
                }
            };
            futures.put(date, executorService.submit(callable));
        }

        List<CompensationTaskResult> completedResults = new ArrayList<CompensationTaskResult>();
        for (Map.Entry<Date, Future<CompensationTaskResult>> entry : futures.entrySet()) {
            Date date = entry.getKey();
            Future<CompensationTaskResult> future = entry.getValue();
            try {
                CompensationTaskResult taskResult = future.get(defaultTimeoutSeconds, TimeUnit.SECONDS);
                completedResults.add(taskResult);
                result.addSuccess(date, taskResult);
            } catch (TimeoutException e) {
                String taskId = generateTaskId(date);
                updateTaskStatus(taskId, CompensationStatus.TIMEOUT);
                result.addFailure(date, "Task timed out after " + defaultTimeoutSeconds + " seconds");
                future.cancel(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                String taskId = generateTaskId(date);
                updateTaskStatus(taskId, CompensationStatus.INTERRUPTED);
                result.addFailure(date, "Task was interrupted");
            } catch (ExecutionException e) {
                String taskId = generateTaskId(date);
                updateTaskStatus(taskId, CompensationStatus.FAILED);
                result.addFailure(date, "Execution error: " + e.getMessage());
            }
        }

        result.setTaskResults(completedResults);
        result.setCompletedAt(new Date());

        if (result.getFailedDates().isEmpty() && result.getTimeoutDates().isEmpty()) {
            result.setStatus(CompensationStatus.COMPLETED);
        } else if (result.getSuccessDates().size() > 0) {
            result.setStatus(CompensationStatus.PARTIALLY_COMPLETED);
        } else {
            result.setStatus(CompensationStatus.FAILED);
        }

        return result;
    }

    private CompensationTaskResult executeSingleCompensation(Date businessDate, String taskId) {
        updateTaskStatus(taskId, CompensationStatus.RUNNING);
        CompensationTaskResult taskResult = new CompensationTaskResult(taskId, businessDate);
        taskResult.setStartedAt(new Date());

        try {
            ReportingBoundaryProcessor.DateBoundaryResult boundaries = boundaryProcessor.calculateDateBoundaries(businessDate);
            taskResult.setBoundaryResult(boundaries);

            List<PaymentDetail> paymentDetails = fetchPaymentDetailsForDate(businessDate);
            List<TransactionDetail> transactionDetails = fetchTransactionsForDate(businessDate);

            taskResult.setRawPaymentCount(paymentDetails.size());
            taskResult.setRawTransactionCount(transactionDetails.size());

            List<PaymentDetail> filteredPayments = boundaryProcessor.filterByEffectiveBoundary(
                    paymentDetails, businessDate,
                    new ReportingBoundaryProcessor.DateExtractor<PaymentDetail>() {
                        @Override
                        public Date extract(PaymentDetail item) {
                            return item.getCreatedDate();
                        }
                    });

            Map<Date, List<PaymentDetail>> grouped = boundaryProcessor.correctCrossDayArchival(paymentDetails);
            taskResult.setFilteredPaymentCount(filteredPayments.size());
            taskResult.setGroupedDates(grouped.keySet().size());

            ReportingBoundaryProcessor.ReconciliationResult reconciliation = boundaryProcessor.reconcileWithRawTransactions(
                    filteredPayments, transactionDetails, businessDate);

            taskResult.setReconciliationResult(reconciliation);
            taskResult.setAlignmentErrors(reconciliation.isAligned()
                    ? Collections.<String>emptyList()
                    : boundaryProcessor.validateAlignment(filteredPayments, transactionDetails, businessDate));

            if (reconciliation.isAligned()) {
                taskResult.setSuccess(true);
                updateTaskStatus(taskId, CompensationStatus.COMPLETED);
            } else {
                taskResult.setSuccess(false);
                taskResult.setRecalculationNeeded(true);
                updateTaskStatus(taskId, CompensationStatus.RECALCULATION_NEEDED);
            }

        } catch (Exception e) {
            taskResult.setSuccess(false);
            taskResult.setErrorMessage("Compensation failed: " + e.getMessage());
            updateTaskStatus(taskId, CompensationStatus.FAILED);
        }

        taskResult.setCompletedAt(new Date());
        return taskResult;
    }

    private List<PaymentDetail> fetchPaymentDetailsForDate(Date businessDate) {
        List<PaymentDetail> result = new ArrayList<PaymentDetail>();
        try {
            Date[] range = TimeBoundaryUtils.getArchivalDateRangeWithDstCorrection(businessDate, reportingTimeZone);
            ReportingPaymentDetailRequest request = new ReportingPaymentDetailRequest();
            request.setConversationId(generateConversationId(businessDate));
            request.setLocale(com.iyzipay.model.Locale.TR.getValue());

            ReportingPaymentDetail reporting = ReportingPaymentDetail.create(request, options);
            if (reporting != null && reporting.getPayments() != null) {
                for (PaymentDetail pd : reporting.getPayments()) {
                    if (pd.getCreatedDate() != null
                            && TimeBoundaryUtils.isTransactionInDateRange(pd.getCreatedDate(), range[0], range[1])) {
                        result.add(pd);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private List<TransactionDetail> fetchTransactionsForDate(Date businessDate) {
        List<TransactionDetail> result = new ArrayList<TransactionDetail>();
        try {
            String dateStr = TimeBoundaryUtils.formatDateOnlyTurkey(businessDate);
            ReportingPaymentTransactionRequest request = new ReportingPaymentTransactionRequest();
            request.setConversationId(generateConversationId(businessDate));
            request.setLocale(com.iyzipay.model.Locale.TR.getValue());
            request.setTransactionDate(dateStr);
            request.setPage(1);

            com.iyzipay.model.ReportingPaymentTransaction reporting =
                    com.iyzipay.model.ReportingPaymentTransaction.create(request, options);
            if (reporting != null && reporting.getTransactions() != null) {
                result.addAll(reporting.getTransactions());
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private String generateTaskId(Date date) {
        return "DST_COMP_" + TimeBoundaryUtils.formatDateOnlyUtc(date) + "_" + System.nanoTime();
    }

    private String generateConversationId(Date date) {
        return "DST_COMP_" + TimeBoundaryUtils.formatDateOnlyUtc(date) + "_" +
                Long.toHexString(Double.doubleToLongBits(Math.random()));
    }

    private void updateTaskStatus(String taskId, CompensationStatus status) {
        CompensationTask task = taskRegistry.get(taskId);
        if (task != null) {
            task.setStatus(status);
            task.setLastUpdatedAt(new Date());
        }
    }

    public CompensationTask getTaskStatus(String taskId) {
        return taskRegistry.get(taskId);
    }

    public List<CompensationTask> getAllTasks() {
        return new ArrayList<CompensationTask>(taskRegistry.values());
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public enum CompensationStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        PARTIALLY_COMPLETED,
        RECALCULATION_NEEDED,
        FAILED,
        TIMEOUT,
        INTERRUPTED,
        NO_DATES_AFFECTED
    }

    public static class CompensationTask {
        private final String taskId;
        private final Date targetDate;
        private CompensationStatus status;
        private Date createdAt;
        private Date lastUpdatedAt;

        public CompensationTask(String taskId, Date targetDate, CompensationStatus status) {
            this.taskId = taskId;
            this.targetDate = targetDate;
            this.status = status;
            this.createdAt = new Date();
            this.lastUpdatedAt = this.createdAt;
        }

        public String getTaskId() { return taskId; }
        public Date getTargetDate() { return targetDate; }
        public CompensationStatus getStatus() { return status; }
        public void setStatus(CompensationStatus status) { this.status = status; }
        public Date getCreatedAt() { return createdAt; }
        public Date getLastUpdatedAt() { return lastUpdatedAt; }
        public void setLastUpdatedAt(Date lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
    }

    public static class CompensationTaskResult {
        private final String taskId;
        private final Date businessDate;
        private boolean success;
        private boolean recalculationNeeded;
        private int rawPaymentCount;
        private int rawTransactionCount;
        private int filteredPaymentCount;
        private int groupedDates;
        private Date startedAt;
        private Date completedAt;
        private String errorMessage;
        private ReportingBoundaryProcessor.DateBoundaryResult boundaryResult;
        private ReportingBoundaryProcessor.ReconciliationResult reconciliationResult;
        private List<String> alignmentErrors;

        public CompensationTaskResult(String taskId, Date businessDate) {
            this.taskId = taskId;
            this.businessDate = businessDate;
            this.alignmentErrors = Collections.emptyList();
        }

        public String getTaskId() { return taskId; }
        public Date getBusinessDate() { return businessDate; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public boolean isRecalculationNeeded() { return recalculationNeeded; }
        public void setRecalculationNeeded(boolean recalculationNeeded) { this.recalculationNeeded = recalculationNeeded; }
        public int getRawPaymentCount() { return rawPaymentCount; }
        public void setRawPaymentCount(int rawPaymentCount) { this.rawPaymentCount = rawPaymentCount; }
        public int getRawTransactionCount() { return rawTransactionCount; }
        public void setRawTransactionCount(int rawTransactionCount) { this.rawTransactionCount = rawTransactionCount; }
        public int getFilteredPaymentCount() { return filteredPaymentCount; }
        public void setFilteredPaymentCount(int filteredPaymentCount) { this.filteredPaymentCount = filteredPaymentCount; }
        public int getGroupedDates() { return groupedDates; }
        public void setGroupedDates(int groupedDates) { this.groupedDates = groupedDates; }
        public Date getStartedAt() { return startedAt; }
        public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
        public Date getCompletedAt() { return completedAt; }
        public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public ReportingBoundaryProcessor.DateBoundaryResult getBoundaryResult() { return boundaryResult; }
        public void setBoundaryResult(ReportingBoundaryProcessor.DateBoundaryResult boundaryResult) { this.boundaryResult = boundaryResult; }
        public ReportingBoundaryProcessor.ReconciliationResult getReconciliationResult() { return reconciliationResult; }
        public void setReconciliationResult(ReportingBoundaryProcessor.ReconciliationResult reconciliationResult) { this.reconciliationResult = reconciliationResult; }
        public List<String> getAlignmentErrors() { return alignmentErrors; }
        public void setAlignmentErrors(List<String> alignmentErrors) { this.alignmentErrors = alignmentErrors; }
    }

    public static class CompensationResult {
        private final int totalDates;
        private Date[] targetDates;
        private CompensationStatus status;
        private Date completedAt;
        private final Map<Date, CompensationTaskResult> successResults;
        private final Map<Date, String> failedDates;
        private final Map<Date, String> timeoutDates;
        private List<CompensationTaskResult> taskResults;

        public CompensationResult(int totalDates) {
            this.totalDates = totalDates;
            this.successResults = new HashMap<Date, CompensationTaskResult>();
            this.failedDates = new LinkedHashMap<Date, String>();
            this.timeoutDates = new LinkedHashMap<Date, String>();
            this.taskResults = new ArrayList<CompensationTaskResult>();
        }

        public void addSuccess(Date date, CompensationTaskResult result) {
            successResults.put(date, result);
        }

        public void addFailure(Date date, String reason) {
            failedDates.put(date, reason);
        }

        public void setTimeoutDates(Map<Date, String> timeoutDates) {
            this.timeoutDates.putAll(timeoutDates);
        }

        public void addTimeout(Date date, String reason) {
            timeoutDates.put(date, reason);
        }

        public int getTotalDates() { return totalDates; }
        public Date[] getTargetDates() { return targetDates; }
        public void setTargetDates(Date[] targetDates) { this.targetDates = targetDates; }
        public CompensationStatus getStatus() { return status; }
        public void setStatus(CompensationStatus status) { this.status = status; }
        public Date getCompletedAt() { return completedAt; }
        public void setCompletedAt(Date completedAt) { this.completedAt = completedAt; }
        public Map<Date, CompensationTaskResult> getSuccessResults() { return successResults; }
        public Map<Date, String> getFailedDates() { return failedDates; }
        public Map<Date, String> getTimeoutDates() { return timeoutDates; }
        public List<CompensationTaskResult> getTaskResults() { return taskResults; }
        public void setTaskResults(List<CompensationTaskResult> taskResults) { this.taskResults = taskResults; }
        public int getSuccessCount() { return successResults.size(); }
        public int getFailedCount() { return failedDates.size(); }
        public int getTimeoutCount() { return timeoutDates.size(); }
        public java.util.Set<Date> getSuccessDates() { return successResults.keySet(); }
    }
}
