package com.npcl.com.vcpopdl.model;

import com.google.gson.annotations.SerializedName;

public class QuickBillResponse {

    @SerializedName("d")
    private Data data;



    public static class Data {
        @SerializedName("__metadata")
        private Metadata metadata;

        @SerializedName("Consumer_No")
        private String consumerNo;

        @SerializedName("Contract_Ac")
        private String contractAc;

        @SerializedName("Name")
        private String name;

        @SerializedName("Mobile")
        private String mobile;

        @SerializedName("Email")
        private String email;

        @SerializedName("Bill_No")
        private String billNo;

        @SerializedName("Bill_Date")
        private String billDate;

        @SerializedName("Bill_Month")
        private String billMonth;

        @SerializedName("Due_Date")
        private String dueDate;

        @SerializedName("Amount")
        private String amount;

        @SerializedName("Current_Outstanding_Amount")
        private String currentOutstandingAmount;

        @SerializedName("Partner_Type")
        private String partnerType;

        @SerializedName("V_Billdoc")
        private String vBilldoc;

        @SerializedName("Bill_Docno")
        private String billDocno;

        @SerializedName("Payer_Name")
        private String payerName;

        @SerializedName("Total_Amount")
        private String totalAmount;

        @SerializedName("Msg_flag")
        private String msgFlag;

        @SerializedName("Message")
        private String message;

        @SerializedName("Disconn_Flag")
        private String disconnFlag;

        @SerializedName("Prepaid_Flag")
        private String prepaidFlag;

        @SerializedName("Meter_Type")
        private String meterType;

        @SerializedName("Meter_No")
        private String meterNo;

        @SerializedName("Premise_Id")
        private String premiseId;

        // ---- Getters ----
        public Metadata getMetadata() { return metadata; }
        public String getConsumerNo() { return consumerNo; }
        public String getContractAc() { return contractAc; }
        public String getName() { return name; }
        public String getMobile() { return mobile; }
        public String getEmail() { return email; }
        public String getBillNo() { return billNo; }
        public String getBillDate() { return billDate; }
        public String getBillMonth() { return billMonth; }
        public String getDueDate() { return dueDate; }
        public String getAmount() { return amount; }
        public String getCurrentOutstandingAmount() { return currentOutstandingAmount; }
        public String getPartnerType() { return partnerType; }
        public String getvBilldoc() { return vBilldoc; }
        public String getBillDocno() { return billDocno; }
        public String getPayerName() { return payerName; }
        public String getTotalAmount() { return totalAmount; }
        public String getMsgFlag() { return msgFlag; }
        public String getMessage() { return message; }
        public String getDisconnFlag() { return disconnFlag; }
        public String getPrepaidFlag() { return prepaidFlag; }
        public String getMeterType() { return meterType; }
        public String getMeterNo() { return meterNo; }
        public String getPremiseId() { return premiseId; }

        // nested metadata object
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
    }

    public Data getData() {return data;}
}
