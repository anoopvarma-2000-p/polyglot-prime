package lib.aide.tabular;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.OrderField;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.ResultQuery;
import org.jooq.SelectConditionStep;
import org.jooq.SelectJoinStep;
import org.jooq.TableLike;
import org.jooq.impl.DSL;
import org.techbd.udi.auto.jooq.ingress.tables.GetFhirNeedsAttention;
import org.techbd.udi.auto.jooq.ingress.tables.GetFhirNeedsAttentionDetails;
import org.techbd.udi.auto.jooq.ingress.tables.GetFhirPatientScreeningQuestionsAnswers;
import org.techbd.udi.auto.jooq.ingress.tables.GetFhirScnSubmission;
import org.techbd.udi.auto.jooq.ingress.tables.GetFhirScnSubmissionDetails;
import org.techbd.udi.auto.jooq.ingress.tables.GetMissingDatalakeSubmissionDetails;
import org.techbd.udi.auto.jooq.ingress.tables.GetMissingTechbydesigndispositionDetails;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JooqRowsSupplierForSP {

    private static final Pattern VALID_COLUMN_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    private final DSLContext dslContext;
    private final String schemaName;
    private final String storedProcName;
    private final String paramsJson;
    private final TabularRowsRequestForSP payload;

    public static final String DATE_TIME_FORMAT_YMDHMS = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_TIME_FORMAT_MDYHMS = "MM-dd-yyyy HH:mm:ss";
    public static final String DATE_TIME_FORMAT_MDY = "MM-dd-yyyy";

    private JooqRowsSupplierForSP(Builder builder) {
        this.dslContext = builder.dslContext;
        this.schemaName = builder.schemaName;
        this.storedProcName = builder.storedProcName;
        this.paramsJson = builder.paramsJson;
        this.payload = builder.payload;
    }

    public static Builder builder(DSLContext dslContext) {
        return new Builder(dslContext);
    }

    private static void validateColumnName(final String name, final String context) {
        if (name == null || !VALID_COLUMN_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid column name in " + context + ": " + name);
        }
    }

    public List<Map<String, Object>> fetchData() throws Exception {
        // Validate all user-supplied column identifiers upfront to prevent SQL injection
        if (payload.filterModel() != null) {
            payload.filterModel().keySet().forEach(f -> validateColumnName(f, "filterModel"));
        }
        if (payload.sortModel() != null) {
            payload.sortModel().forEach(sort -> validateColumnName(sort.colId(), "sortModel"));
        }

        // Construct base query
        SelectJoinStep<Record> baseQuery = dslContext.select().from(getDynamicTablelike(storedProcName, paramsJson));

        // Apply filters
        Condition conditions = buildConditions(payload);

        // Apply pagination and sorting
        SelectConditionStep<?> conditionQuery = baseQuery.where(conditions);
        ResultQuery<?> finalQuery = applySortingAndPagination(conditionQuery, payload);

        // Fetch results
        Result<?> result = finalQuery.fetch();

        // Use different formatting based on stored procedure
        if ("get_fhir_patient_screening_questions_answers".equals(storedProcName)
                || "get_csv_data_integrity_errors".equals(storedProcName)) {
            return result.intoMaps();
        } else {
            return formatData(result.intoMaps());
        }
    }

    private Condition buildConditions(TabularRowsRequestForSP payload) {
        Condition conditions = DSL.trueCondition();

        if (payload.filterModel() != null && !payload.filterModel().isEmpty()) {
            for (Map.Entry<String, TabularRowsRequestForSP.FilterModel> entry : payload.filterModel().entrySet()) {
                String column = entry.getKey();
                TabularRowsRequestForSP.FilterModel filterModel = entry.getValue();

                if (filterModel.conditions() != null && !filterModel.conditions().isEmpty()) {
                    // Handle multiple conditions for the column
                    List<Condition> subConditions = filterModel.conditions().stream()
                            .map(condition -> buildSingleCondition(column, condition))
                            .collect(Collectors.toList());

                    Condition combinedSubConditions = combineConditions(subConditions, filterModel.operator());
                    conditions = combineConditions(List.of(conditions, combinedSubConditions), "AND");
                } else {
                    // Handle a single condition for the column
                    Condition singleCondition = buildSingleCondition(column, filterModel);
                    conditions = combineConditions(List.of(conditions, singleCondition), "AND");
                }
            }
        }

        return conditions;
    }

    private Condition combineConditions(List<Condition> conditions, String operator) {
        if (conditions == null || conditions.isEmpty()) {
            return DSL.trueCondition();
        }

        return conditions.stream().reduce((cond1, cond2) -> {
            switch (operator.toUpperCase()) {
                case "AND" -> {
                    return cond1.and(cond2);
                }
                case "OR" -> {
                    return cond1.or(cond2);
                }
                default ->
                    throw new IllegalArgumentException("Invalid operator in the payload: " + operator);
            }
        }).orElse(DSL.trueCondition());
    }

    private Condition buildSingleCondition(String column, TabularRowsRequestForSP.FilterCondition condition) {
        final var colField = DSL.field(DSL.name(column));
        switch (condition.filterType()) {
            case "number" -> {
                return switch (condition.type()) { // Total 9 conditions for agNumberColumnFilter
                    case "equals" ->
                        colField.eq(condition.filter());
                    case "notEqual" ->
                        colField.ne(condition.filter());
                    case "lessThan" ->
                        colField.lt(condition.filter());
                    case "lessThanOrEqual" ->
                        colField.le(condition.filter());
                    case "greaterThan" ->
                        colField.gt(condition.filter());
                    case "greaterThanOrEqual" ->
                        colField.ge(condition.filter());
                    case "inRange" ->
                        colField.between(condition.filter(), condition.filterTo());
                    case "blank" ->
                        colField.isNull();
                    case "notBlank" ->
                        colField.isNotNull();
                    default ->
                        throw new IllegalArgumentException(
                                "Unsupported condition type in payload: " + condition.type());
                };
            }
            case "text" -> { // Total 8 conditions for agTextColumnFilter
                return switch (condition.type()) {
                    case "contains" ->
                        colField.contains((String) condition.filter());
                    case "notContains" ->
                        colField.notContains((String) condition.filter());
                    case "equals" ->
                        colField.equalIgnoreCase((String) condition.filter());
                    case "notEqual" ->
                        DSL.lower(colField.cast(String.class)).ne(DSL.lower(DSL.val((String) condition.filter())));
                    case "startsWith" ->
                        colField.startsWithIgnoreCase((String) condition.filter());
                    case "endsWith" ->
                        colField.endsWithIgnoreCase((String) condition.filter());
                    case "blank" ->
                        colField.isNull();
                    case "notBlank" ->
                        colField.isNotNull();
                    default ->
                        throw new IllegalArgumentException(
                                "Unsupported condition type in payload: " + condition.type());
                };
            }
            case "date" -> {
                return switch (condition.type()) {
                    case "equals" ->
                        colField.eq(convertStringToLocalDate(condition.dateFrom(), DATE_TIME_FORMAT_YMDHMS));
                    case "notEqual" ->
                        colField.ne(convertStringToLocalDate(condition.dateFrom(), DATE_TIME_FORMAT_YMDHMS));
                    case "lessThan" ->
                        colField.lt(convertStringToLocalDate(condition.dateFrom(), DATE_TIME_FORMAT_YMDHMS));
                    case "greaterThan" ->
                        colField.gt(convertStringToLocalDate(condition.dateFrom(), DATE_TIME_FORMAT_YMDHMS));
                    case "inRange" ->
                        colField.between(
                                convertStringToLocalDate(condition.dateFrom(), DATE_TIME_FORMAT_YMDHMS),
                                convertStringToLocalDate(condition.dateTo(), DATE_TIME_FORMAT_YMDHMS));
                    case "blank" ->
                        colField.isNull();
                    case "notBlank" ->
                        colField.isNotNull();
                    default ->
                        throw new IllegalArgumentException(
                                "Unsupported condition type in payload: " + condition.type());
                };
            }
            case "boolean" -> { // Reserved for future use
                throw new IllegalArgumentException("Unsupported filter type in payload: " + condition.filterType());
            }
            case "set" -> { // Reserved for future use
                throw new IllegalArgumentException("Unsupported filter type in payload: " + condition.filterType());
            }
            case "multi" -> { // Reserved for future use
                throw new IllegalArgumentException("Unsupported filter type in payload: " + condition.filterType());
            }
            case "custom" -> { // Reserved for future use
                throw new IllegalArgumentException("Unsupported filter type in payload: " + condition.filterType());
            }
            default ->
                throw new IllegalArgumentException("Unsupported filter type in payload: " + condition.filterType());
        }
    }

    private Condition buildSingleCondition(String column, TabularRowsRequestForSP.FilterModel filterModel) {
        final var colField = DSL.field(DSL.name(column));
        switch (filterModel.filterType()) {
            case "number" -> { // Total 9 conditions for agNumberColumnFilter
                return switch (filterModel.type()) {
                    case "equals" ->
                        colField.eq(filterModel.filter());
                    case "notEqual" ->
                        colField.ne(filterModel.filter());
                    case "lessThan" ->
                        colField.lt(filterModel.filter());
                    case "lessThanOrEqual" ->
                        colField.le(filterModel.filter());
                    case "greaterThan" ->
                        colField.gt(filterModel.filter());
                    case "greaterThanOrEqual" ->
                        colField.ge(filterModel.filter());
                    case "inRange" ->
                        colField.between(filterModel.filter(), filterModel.filterTo());
                    case "blank" ->
                        colField.isNull();
                    case "notBlank" ->
                        colField.isNotNull();
                    default ->
                        throw new IllegalArgumentException(
                                "Unsupported condition type in payload: " + filterModel.type());
                };
            }
            case "text" -> { // Total 8 conditions for agTextColumnFilter
                return switch (filterModel.type()) {
                    case "contains" ->
                        colField.contains((String) filterModel.filter());
                    case "notContains" ->
                        colField.notContains((String) filterModel.filter());
                    case "equals" ->
                        colField.equalIgnoreCase((String) filterModel.filter());
                    case "notEqual" ->
                        DSL.lower(colField.cast(String.class))
                                .ne(DSL.lower(DSL.val((String) filterModel.filter())));
                    case "startsWith" ->
                        colField.startsWithIgnoreCase((String) filterModel.filter());
                    case "endsWith" ->
                        colField.endsWithIgnoreCase((String) filterModel.filter());
                    case "blank" ->
                        colField.isNull();
                    case "notBlank" ->
                        colField.isNotNull();
                    default ->
                        throw new IllegalArgumentException(
                                "Unsupported condition type in payload: " + filterModel.type());
                };
            }
            case "date" -> {
                return switch (filterModel.type()) {
                    case "equals" ->
                        colField.eq(convertStringToLocalDate(filterModel.dateFrom(), DATE_TIME_FORMAT_YMDHMS));
                    case "notEqual" ->
                        colField.ne(convertStringToLocalDate(filterModel.dateFrom(), DATE_TIME_FORMAT_YMDHMS));
                    case "lessThan" ->
                        colField.lt(convertStringToLocalDate(filterModel.dateFrom(), DATE_TIME_FORMAT_YMDHMS));
                    case "greaterThan" ->
                        colField.gt(convertStringToLocalDate(filterModel.dateFrom(), DATE_TIME_FORMAT_YMDHMS));
                    case "inRange" ->
                        colField.between(
                                convertStringToLocalDate(filterModel.dateFrom(), DATE_TIME_FORMAT_YMDHMS),
                                convertStringToLocalDate(filterModel.dateTo(), DATE_TIME_FORMAT_YMDHMS));
                    case "blank" ->
                        colField.isNull();
                    case "notBlank" ->
                        colField.isNotNull();
                    default ->
                        throw new IllegalArgumentException(
                                "Unsupported condition type in payload: " + filterModel.type());
                };
            }
            case "boolean" -> { // Reserved for future use
                throw new IllegalArgumentException("Unsupported filter type in payload: " + filterModel.filterType());
            }
            case "set" -> { // Reserved for future use
                throw new IllegalArgumentException("Unsupported filter type in payload: " + filterModel.filterType());
            }
            case "multi" -> { // Reserved for future use
                throw new IllegalArgumentException("Unsupported filter type in payload: " + filterModel.filterType());
            }
            case "custom" -> { // Reserved for future use
                throw new IllegalArgumentException("Unsupported filter type in payload: " + filterModel.filterType());
            }
            default ->
                throw new IllegalArgumentException("Unsupported filter type in payload: " + filterModel.filterType());
        }
    }

    ResultQuery<?> applySortingAndPagination(SelectConditionStep<?> query, TabularRowsRequestForSP payload) {
        List<OrderField<?>> orderFields = new ArrayList<>();
        if (payload.sortModel() != null && !payload.sortModel().isEmpty()) {
            for (TabularRowsRequestForSP.SortModel sortModel : payload.sortModel()) {
                validateColumnName(sortModel.colId(), "sortModel");
                Field<?> field = DSL.field(DSL.name(sortModel.colId()));
                if ("asc".equalsIgnoreCase(sortModel.sort())) {
                    orderFields.add(field.asc());
                } else if ("desc".equalsIgnoreCase(sortModel.sort())) {
                    orderFields.add(field.desc());
                }
            }
            return query.orderBy(orderFields).limit(payload.startRow(), payload.endRow() - payload.startRow());
        }
        return query.limit(payload.startRow(), payload.endRow() - payload.startRow());
    }

    LocalDate convertStringToLocalDate(String dateString, String pattern) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            return LocalDate.parse(dateString, formatter);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format: " + dateString, e);
        }
    }

    private List<Map<String, Object>> formatData(List<Map<String, Object>> data) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT_MDYHMS);
        DateTimeFormatter mmddyyyyFormatter = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT_MDY);

        return data.stream()
                .map(row -> row.entrySet().stream()
                        .filter(e -> e.getKey() != null)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> {
                                    Object value = entry.getValue();
                                    try {
                                        if (value != null) {
                                            if (value instanceof OffsetDateTime offsetDateTime) {
                                                return offsetDateTime
                                                        .atZoneSameInstant(ZoneId.of("America/New_York"))
                                                        .toLocalDateTime()
                                                        .format(formatter);
                                            } else if (value instanceof LocalDate localDate) {
                                                return localDate.format(mmddyyyyFormatter);
                                            } else if (value instanceof java.sql.Date sqlDate) {
                                                return sqlDate.toLocalDate().format(mmddyyyyFormatter);
                                            } else {
                                                return value;
                                            }
                                        }
                                        return "";
                                    } catch (Exception ex) {
                                        return "";
                                    }
                                },
                                (v1, v2) -> v1
                        )))
                .collect(Collectors.toList());
    }

    TableLike<?> getDynamicTablelike(String storedProcName, String paramsJson) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT_MDY);

        switch (storedProcName) {
            case "get_fhir_scn_submission" -> {
                Map<String, LocalDate> paramMap = parseDates(paramsJson, objectMapper, formatter);
                return new GetFhirScnSubmission().call(paramMap.get("start_date"), paramMap.get("end_date"));
            }
            case "get_fhir_scn_submission_details" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> dateMap = objectMapper.readValue(paramsJson, Map.class);

                String tenantId = dateMap.get("tenant_id");
                formatter = DateTimeFormatter.ofPattern("MM-dd-yyyy");
                LocalDate localStartDate = LocalDate.parse(dateMap.get("start_date"), formatter);
                LocalDate localEndDate = LocalDate.parse(dateMap.get("end_date"), formatter);

                return new GetFhirScnSubmissionDetails().call(tenantId, localStartDate, localEndDate);
            }
            case "get_fhir_needs_attention" -> {
                Map<String, LocalDate> paramMap = parseDates(paramsJson, objectMapper, formatter);
                return new GetFhirNeedsAttention().call(paramMap.get("start_date"), paramMap.get("end_date"));
            }
            case "get_interaction_observe" -> {
                Map<String, LocalDate> paramMap = parseDates(paramsJson, objectMapper, formatter);
                LocalDate startDate = paramMap.get("start_date");
                LocalDate endDate = paramMap.get("end_date");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?, ?)", startDate, endDate));
            }
            case "get_api_interaction_observe" -> {
                Map<String, LocalDate> paramMap = parseDates(paramsJson, objectMapper, formatter);
                LocalDate startDate = paramMap.get("start_date");
                LocalDate endDate = paramMap.get("end_date");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?, ?)", startDate, endDate));
            }
            case "get_user_interaction_observe" -> {
                Map<String, LocalDate> paramMap = parseDates(paramsJson, objectMapper, formatter);
                LocalDate startDate = paramMap.get("start_date");
                LocalDate endDate = paramMap.get("end_date");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?, ?)", startDate, endDate));
            }
            case "get_fhir_patient_screening_questions_answers" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> paramMap = objectMapper.readValue(paramsJson, Map.class);

                String hubInteractionId = paramMap.get("p_hub_interaction_id");
                String patientMrn = paramMap.get("p_patient_mrn");

                return new GetFhirPatientScreeningQuestionsAnswers().call(hubInteractionId, patientMrn);
            }
            case "get_csv_data_integrity_errors" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> paramMap = objectMapper.readValue(paramsJson, Map.class);
                String zipFileHubInteractionId = paramMap.get("p_zip_file_hub_interaction_id");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?)", zipFileHubInteractionId));
            }
            case "get_fhir_session_diagnostics" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> paramMap = objectMapper.readValue(paramsJson, Map.class);
                String startDate = paramMap.get("p_start_date");
                String endDate = paramMap.get("p_end_date");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?, ?)", startDate, endDate));
            }
            case "get_fhir_session_diagnostics_details" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> paramMap = objectMapper.readValue(paramsJson, Map.class);
                String startDate = paramMap.get("p_start_date");
                String endDate = paramMap.get("p_end_date");
                String hubInteractionId = paramMap.get("p_hub_interaction_id");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?, ?, ?)",
                        startDate, endDate, hubInteractionId));
            }
            case "get_fhir_validation_issue_details" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> paramMap = objectMapper.readValue(paramsJson, Map.class);
                String igVersion = paramMap.get("p_ig_version");
                String validationEngine = paramMap.get("p_validation_engine");
                String issueDate = paramMap.get("p_issue_date");
                String source = paramMap.get("p_source");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?, ?, ?, ?)",
                        igVersion, validationEngine, issueDate, source));
            }
            case "get_interaction_http_request" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> paramMap = objectMapper.readValue(paramsJson, Map.class);
                String startDate = paramMap.get("start_date");
                String endDate = paramMap.get("end_date");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?, ?)", startDate, endDate));
            }
            case "get_fhir_screening_info" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> paramMap = objectMapper.readValue(paramsJson, Map.class);
                String qeName     = paramMap.get("p_qe_name");
                String patientMrn = paramMap.get("p_patient_mrn");
                String orgId      = paramMap.get("p_org_id");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?, ?, ?)",
                        qeName, patientMrn, orgId));
            }
            case "get_interaction_csv_http_fhir_request_details" -> {
                objectMapper = new ObjectMapper();
                Map<String, String> paramMap = objectMapper.readValue(paramsJson, Map.class);
                String sourceHubInteractionId = paramMap.get("p_source_hub_interaction_id");
                return DSL.table(DSL.sql(schemaName + "." + storedProcName + "(?)", sourceHubInteractionId));
            }
            case "get_fhir_needs_attention_details", "get_missing_datalake_submission_details", "get_missing_techbydesigndisposition_details" -> {
                Map<String, LocalDate> dateMap = parseDates(paramsJson, objectMapper, formatter);
                Map<String, String> paramsMap = objectMapper.readValue(paramsJson, Map.class);
                String tenantId = paramsMap.get("tenant_id").toLowerCase();
                LocalDate startDate = dateMap.get("start_date");
                LocalDate endDate = dateMap.get("end_date");
                if (storedProcName.equals("get_fhir_needs_attention_details")) {
                    return new GetFhirNeedsAttentionDetails()
                            .call(tenantId, startDate, endDate);
                } else if (storedProcName.equals("get_missing_datalake_submission_details")) {
                    return new GetMissingDatalakeSubmissionDetails()
                            .call(tenantId, startDate, endDate);
                } else {
                    return new GetMissingTechbydesigndispositionDetails()
                            .call(tenantId, startDate, endDate);
                }
            }
            default ->
                throw new IllegalArgumentException("Invalid stored procedure name: " + storedProcName);
        }
    }

    private Map<String, LocalDate> parseDates(String paramsJson, ObjectMapper objectMapper, DateTimeFormatter formatter)
            throws Exception {
        Map<String, String> stringMap = objectMapper.readValue(paramsJson, Map.class);
        LocalDate startDate = LocalDate.parse(stringMap.get("start_date"), formatter);
        LocalDate endDate = LocalDate.parse(stringMap.get("end_date"), formatter);
        return Map.of(
                "start_date", startDate,
                "end_date", endDate);
    }

    public static class Builder {

        private final DSLContext dslContext;
        private String schemaName;
        private String storedProcName;
        private String paramsJson;
        private TabularRowsRequestForSP payload;

        private Builder(DSLContext dslContext) {
            this.dslContext = dslContext;
        }

        public Builder withSchemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        public Builder withStoredProcName(String storedProcName) {
            this.storedProcName = storedProcName;
            return this;
        }

        public Builder withParamsJson(String paramsJson) {
            this.paramsJson = paramsJson;
            return this;
        }

        public Builder withPayload(TabularRowsRequestForSP payload) {
            this.payload = payload;
            return this;
        }

        public JooqRowsSupplierForSP build() {
            return new JooqRowsSupplierForSP(this);
        }
    }
}
