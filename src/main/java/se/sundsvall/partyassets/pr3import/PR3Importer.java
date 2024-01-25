package se.sundsvall.partyassets.pr3import;

import static com.nimbusds.oauth2.sdk.util.StringUtils.isNotBlank;
import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.validation.Validator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.dhatim.fastexcel.Color;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zalando.problem.ThrowableProblem;

import se.sundsvall.partyassets.api.model.AssetCreateRequest;
import se.sundsvall.partyassets.api.model.Status;
import se.sundsvall.partyassets.integration.party.PartyClient;
import se.sundsvall.partyassets.service.AssetService;

import generated.se.sundsvall.party.PartyType;

@Component
@ConditionalOnProperty(name = "pr3import.enabled", havingValue = "true", matchIfMissing = true)
class PR3Importer {

    private static final String PARAM_REGISTRATION_NUMBER = "registrationNumber";
    private static final String PARAM_CARD_PRINTED = "cardPrinted";
    private static final String PARAM_SMART_PARK_SYNC = "smartParkSync";
    private static final String PARAM_ISSUED_BY_ADMINISTRATION = "issuedByAdministration";
    private static final String PARAM_ISSUED_BY_ADMINISTRATOR = "issuedByAdministrator";
    private static final String PARAM_APPLIED_AS = "appliedAs";
    private static final String PARAM_PERMIT_FULL_NUMBER = "permitFullNumber";

    private static final String DRIVER = "driver";
    private static final String PASSENGER = "passenger";
    private static final String DRIVER_SHORT = "F";
    private static final String PASSENGER_SHORT = "P";

    private final PR3ImportProperties properties;
    private final AssetService assetService;
    private final PartyClient partyClient;
    private final Validator validator;

    PR3Importer(final PR3ImportProperties properties, final AssetService assetService,
            final PartyClient partyClient, final Validator validator) {
        this.properties = properties;
        this.assetService = assetService;
        this.partyClient = partyClient;
        this.validator = validator;
    }

    /**
     * Imports assets from the Excel file read from the given input stream.
     *
     * @param in the input stream to read the Excel file from.
     * @return the import result.
     * @throws IOException on any errors.
     */
    Result importFromExcel(final InputStream in) throws IOException {
        var result = new Result();

        var lastFailedRowIndex = 1;
        var out = new ByteArrayOutputStream();

        try (var sourceWorkbook = new ReadableWorkbook(in);
             var failedEntriesWorkbook = new Workbook(out, "party-assets", null)) {
            var sourceSheet = sourceWorkbook.getFirstSheet();
            var failedEntriesSheet = failedEntriesWorkbook.newWorksheet(sourceSheet.getName());
            // Sort the rows on asset id (TILLSTNR), descending
            var rows = sourceSheet.read().stream()
                .sorted(comparing(r -> r.getCellText(7)))
                .toList()
                .reversed();

            result.setTotal(rows.size() - 1);

            // Copy the header row
            copyHeaderRow(rows.getFirst(), failedEntriesSheet);

            // Process the rest of the rows
            for (var rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                var row = rows.get(rowIndex);

                // Create an asset (create request) and fill in the static info
                var assetCreateRequest = new AssetCreateRequest()
                    .withOrigin(properties.staticAssetInfo().origin())
                    .withType(properties.staticAssetInfo().type())
                    .withDescription(properties.staticAssetInfo().description());

                // Fill in the rest of the asset information from the current row
                extractAssetId(row).ifPresent(assetCreateRequest::setAssetId);
                var legalId = extractLegalId(row);
                legalId
                    // Clean the legal id
                    .map(this::cleanLegalId)
                    // Add a century digit to the legal id, if needed
                    .map(this::addCenturyDigitToLegalId)
                    // Get the party id
                    .flatMap(cleanLegalId -> partyClient.getPartyId(PartyType.PRIVATE, cleanLegalId))
                    .ifPresent(assetCreateRequest::setPartyId);
                extractIssuedDate(row).ifPresent(assetCreateRequest::setIssued);
                extractValidToDate(row).ifPresent(assetCreateRequest::setValidTo);
                extractStatus(row).ifPresent(assetCreateRequest::setStatus);
                extractRegistrationNumber(row).ifPresent(value ->
                    assetCreateRequest.setAdditionalParameter(PARAM_REGISTRATION_NUMBER, value));
                extractCardPrinted(row).ifPresent(value ->
                    assetCreateRequest.setAdditionalParameter(PARAM_CARD_PRINTED, value.format(DateTimeFormatter.ISO_DATE)));
                extractSmartParkSync(row).ifPresent(value ->
                    assetCreateRequest.setAdditionalParameter(PARAM_SMART_PARK_SYNC, value));
                extractIssuedByAdministration(row).ifPresent(value ->
                    assetCreateRequest.setAdditionalParameter(PARAM_ISSUED_BY_ADMINISTRATION, value));
                extractIssuedByAdministrator(row).ifPresent(value ->
                    assetCreateRequest.setAdditionalParameter(PARAM_ISSUED_BY_ADMINISTRATOR, value));
                extractAppliedAs(row).ifPresent(value ->
                    assetCreateRequest.setAdditionalParameter(PARAM_APPLIED_AS, value));
                // Create the full permit number as {municipality id}-{asset id}-{birth year}{sex}-{applied as}
                extractSex(row).ifPresent(sex -> {
                    // Sanity check...
                    var assetId = assetCreateRequest.getAssetId();
                    var appliedAs = switch (assetCreateRequest.getAdditionalParameters().get(PARAM_APPLIED_AS)) {
                        case DRIVER -> DRIVER_SHORT;
                        case PASSENGER -> PASSENGER_SHORT;
                        default -> null;
                    };
                    var birthYear = legalId.map(value -> value.substring(0, 2)).orElse(null);

                    if (isNotBlank(assetId) && isNotBlank(birthYear) && isNotBlank(appliedAs)) {
                        var permitFullNumber = String.format("%s-%s-%s%s-%s",
                            properties.staticAssetInfo().municipalityId(), assetId, birthYear, sex, appliedAs);

                        assetCreateRequest.setAdditionalParameter(PARAM_PERMIT_FULL_NUMBER, permitFullNumber);
                    }
                });

                var errorDetail = Optional.<String>empty();

                // Validate the asset
                var constraintViolations = validator.validate(assetCreateRequest);
                if (constraintViolations.isEmpty()) {
                    // Save the asset
                    try {
                        assetService.createAsset(assetCreateRequest);
                    } catch (Exception e) {
                        if (e instanceof ThrowableProblem p) {
                            errorDetail = ofNullable(p.getDetail());
                        } else {
                            errorDetail = ofNullable(e.getMessage());
                        }
                    }
                } else {
                    errorDetail = ofNullable(constraintViolations.stream()
                        .map(cv -> cv.getPropertyPath().toString() + " " + cv.getMessage())
                        .collect(Collectors.joining(", ")));
                }

                // We have a failed row - copy it and the error details
                if (errorDetail.isPresent()) {
                    copyRow(row, failedEntriesSheet, lastFailedRowIndex++, errorDetail);
                }
            }
        }

        return result
            .withFailed(lastFailedRowIndex - 1)
            .withFailedExcelData(out.toByteArray());
    }

    /**
     * Copies the given source row to the target worksheet and sets the background to gray.
     *
     * @param sourceRow the source row.
     * @param target the target worksheet.
     */
    private void copyHeaderRow(final Row sourceRow, final Worksheet target) {
        copyRow(sourceRow, target, 0, Optional.empty());

        for (var colIndex = 0; colIndex < sourceRow.getCellCount(); colIndex++) {
            target.style(0, colIndex).fillColor(Color.GRAY3).set();
        }
    }

    /**
     * Copies the given source row to the given row index in the target worksheet, optionally adding
     * an additional "details" column at the end of the row.
     *
     * @param sourceRow the source row.
     * @param target the target worksheet.
     * @param targetRowIndex the target row index.
     * @param optionalDetail an {@code Optional} that may hold additional "details" that is added to
     * the end of the row if non-empty.
     */
    private void copyRow(final Row sourceRow, final Worksheet target, final int targetRowIndex, final Optional<String> optionalDetail) {
        var columnCount = sourceRow.getCellCount();

        for (var colIndex = 0; colIndex < columnCount; colIndex++) {
            target.value(targetRowIndex, colIndex, sourceRow.getCellText(colIndex));
        }

        optionalDetail.ifPresent(detail -> {
            target.value(targetRowIndex, columnCount + 1, detail);
            target.style(targetRowIndex, columnCount + 1).fontColor(Color.RED).set();
        });
    }

    /**
     * Cleans and returns the provided legal id, removing everything but digits.
     *
     * @param legalId the legal id to clean.
     * @return a legal id with digits only
     */
    private String cleanLegalId(final String legalId) {
        return legalId.replaceAll("\\D", "");
    }

    /**
     * Naively adds a century digit to the given legal id, if it's missing.
     *
     * @param legalId the legal id to add the century digit to.
     * @return the original legal id with a leading century digit, if it was previously missing.
     */
    String addCenturyDigitToLegalId(final String legalId) {
        // Make sure we have digits only
        if (legalId.isBlank() || !legalId.matches("^\\d+$")) {
            return null;
        }
        // Do nothing if we already have a legal id with century digits
        if (legalId.startsWith("19") || legalId.startsWith("20")) {
            return legalId;
        }

        // Naively validate
        var thisYear = LocalDate.now().getYear() % 2000;
        var legalIdYear = Integer.parseInt(legalId.substring(0, 2));

        return (legalIdYear <= thisYear ? "20" : "19") + legalId;
    }

    /**
     * Extracts the legal id from the given row (column 10, "PERSONNR").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the legal id, or is empty.
     */
    private Optional<String> extractLegalId(final Row row) {
        return extractCell(row, 10);
    }

    /**
     * Extracts the asset id from the given row (column 7, "TILLSTNR").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the asset id, or is empty.
     */
    private Optional<String> extractAssetId(final Row row) {
        return extractCell(row, 7);
    }

    /**
     * Extracts the issued date from the given row (column 16, "UTFARDAT").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the issued date, or is empty.
     */
    private Optional<LocalDate> extractIssuedDate(final Row row) {
        return row.getCellAsDate(16).map(LocalDateTime::toLocalDate);
    }

    /**
     * Extracts the valid-to date from the given row (column 18, "GILTIGTTOM").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the valid-to date, or is empty.
     */
    private Optional<LocalDate> extractValidToDate(final Row row) {
        return row.getCellAsDate(18).map(LocalDateTime::toLocalDate);
    }

    /**
     * Extracts the status from the given row, by checking whether the valid-to date is after today
     * or not.
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the status, or is empty.
     */
    private Optional<Status> extractStatus(final Row row) {
        return extractValidToDate(row)
            .map(validToDate -> validToDate.isAfter(LocalDate.now()) ? Status.ACTIVE : Status.EXPIRED);
    }

    /**
     * Extracts the registration number from the given row (column 15, "DIARIENR").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the registration number, or is empty.
     */
    private Optional<String> extractRegistrationNumber(final Row row) {
        return extractCell(row, 15);
    }

    /**
     * Extracts the date the card was printed from the given row (column 21, "UTSKRIVET").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the date the card was printed, or is empty.
     */
    private Optional<LocalDateTime> extractCardPrinted(final Row row) {
        return row.getCellAsDate(21);
    }

    /**
     * Extracts the "SmartCardSync" flag from the given row (column 27, "SmartParkSync").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains value of the "SmartParkSync" flag, or is empty.
     */
    private Optional<String> extractSmartParkSync(final Row row) {
        return extractCell(row, 27)
            .map(Integer::parseInt)
            .map(intValue -> switch (intValue) {
                case 0 -> "false";
                case 1 -> "true";
                default -> null;
            });
    }

    /**
     * Extracts the (name of the) administration that issued the card from the given row (column 23, "EXTRA1").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the (name of the) administration that issued
     * the card, or is empty.
     */
    private Optional<String> extractIssuedByAdministration(final Row row) {
        return extractCell(row, 23);
    }

    /**
     * Extracts the (name of the) administrator that issued the card from the given row (column 24, "EXTRA2").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the (name of the) administrator that issued
     * the card, or is empty.
     */
    private Optional<String> extractIssuedByAdministrator(final Row row) {
        return extractCell(row, 24);
    }

    /**
     * Extracts whether the card was applied for as a driver or passenger from the given row (column 5, "PASSAGE").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains whether the card was applied for as a driver
     * or passenger, or is empty.
     */
    private Optional<String> extractAppliedAs(final Row row) {
        return extractCell(row, 5)
            .map(Integer::parseInt)
            .map(intValue -> switch (intValue) {
                case 1 -> PASSENGER;
                case 2 -> DRIVER;
                default -> null;
            });
    }

    /**
     * Extracts the sex as "K" for women and "M" for men, from the given row (column 4, "KON").
     *
     * @param row the row.
     * @return an {@code Optional} that either contains the sex, or is empty.
     */
    private Optional<String> extractSex(final Row row) {
        return extractCell(row, 4)
            .map(Integer::parseInt)
            .map(intValue -> switch (intValue) {
                case 0 -> "K";
                case 1 -> "M";
                default -> null;
            });
    }

    /**
     * Convenience method to extract the contents of a cell as a string, regardless of what type
     * Excel treats it as.
     *
     * @param row the row.
     * @param cellIndex the cell index.
     * @return an {@code Optional} that either contains the cell value as a string, or is empty
     */
    private Optional<String> extractCell(final Row row, final int cellIndex) {
        return Optional.of(row.getCellText(cellIndex)).filter(not(String::isBlank));
    }

    static class Result {

        private int total;
        private int failed;
        @JsonIgnore
        private byte[] failedExcelData;

        public int getTotal() {
            return total;
        }

        void setTotal(final int total) {
            this.total = total;
        }

        Result withTotal(final int total) {
            this.total = total;
            return this;
        }

        public int getFailed() {
            return failed;
        }

        void setFailed(final int failed) {
            this.failed = failed;
        }

        Result withFailed(final int failed) {
            this.failed = failed;
            return this;
        }

        public int getSuccessful() {
            return total - failed;
        }

        byte[] getFailedExcelData() {
            return failedExcelData;
        }

        void setFailedExcelData(final byte[] failedExcelData) {
            this.failedExcelData = failedExcelData;
        }

        Result withFailedExcelData(final byte[] failedExcelData) {
            this.failedExcelData = failedExcelData;
            return this;
        }
    }
}
