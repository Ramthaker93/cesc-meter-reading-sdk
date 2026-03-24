package com.npcl.com.vcpopdl.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class InvoicesResponse {
    @SerializedName("d")
    private Data data;

    public Data getData() {
        return data;
    }

    // ==========================
    // Inner classes
    // ==========================
    public static class Data {

        @SerializedName("results")
        private List<Result> results;

        public List<Result> getResults() {
            return results;
        }

        // --------------------------
        // Each result (Invoice entry)
        // --------------------------
        public static class Result {

            @SerializedName("__metadata")
            private Metadata metadata;

            @SerializedName("InvoiceID")
            private String invoiceId;

            @SerializedName("AccountID")
            private String accountId;

            @SerializedName("ContractAccountID")
            private String contractAccountId;

            @SerializedName("AmountDue")
            private String amountDue;

            @SerializedName("Currency")
            private String currency;

            @SerializedName("DueDate")
            private String dueDate;

            @SerializedName("InvoiceDate")
            private String invoiceDate;

            @SerializedName("AmountPaid")
            private String amountPaid;

            @SerializedName("AmountRemaining")
            private String amountRemaining;

            @SerializedName("InvoiceDescription")
            private String invoiceDescription;

            @SerializedName("InvoiceStatusID")
            private String invoiceStatusId;

            @SerializedName("InvoicePDF")
            private DeferredObject invoicePDF;

            @SerializedName("ContractAccount")
            private DeferredObject contractAccount;

            // -------- Getters --------
            public Metadata getMetadata() { return metadata; }
            public String getInvoiceId() { return invoiceId; }
            public String getAccountId() { return accountId; }
            public String getContractAccountId() { return contractAccountId; }
            public String getAmountDue() { return amountDue; }
            public String getCurrency() { return currency; }
            public String getDueDate() { return dueDate; }
            public String getInvoiceDate() { return invoiceDate; }
            public String getAmountPaid() { return amountPaid; }
            public String getAmountRemaining() { return amountRemaining; }
            public String getInvoiceDescription() { return invoiceDescription; }
            public String getInvoiceStatusId() { return invoiceStatusId; }
            public DeferredObject getInvoicePDF() { return invoicePDF; }
            public DeferredObject getContractAccount() { return contractAccount; }
        }

        // --------------------------
        // Nested Metadata object
        // --------------------------
        public static class Metadata {
            @SerializedName("id")
            private String id;

            @SerializedName("uri")
            private String uri;

            @SerializedName("type")
            private String type;

            public String getId() { return id; }
            public String getUri() { return uri; }
            public String getType() { return type; }
        }

        // --------------------------
        // Deferred (URI) objects
        // --------------------------
        public static class DeferredObject {
            @SerializedName("__deferred")
            private Deferred deferred;

            public Deferred getDeferred() { return deferred; }

            public static class Deferred {
                @SerializedName("uri")
                private String uri;

                public String getUri() { return uri; }
            }
        }
    }
}
