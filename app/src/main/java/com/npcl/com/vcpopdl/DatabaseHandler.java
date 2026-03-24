package com.npcl.com.vcpopdl;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import android.os.Environment;

public class DatabaseHandler  {

    private static final int DATABASE_VERSION = 1;
    private Context context = null;
    //Context applicationContext = context.getApplicationContext();
   // private  String DB_PATH =  applicationContext.getFilesDir().getParent();
    private static final String DATABASE_NAME = "MeterReading";
    SQLiteDatabase db;

    public DatabaseHandler(Context context) {
        this.context = context;
        db = openDatabase();
    }
    public boolean ExecuteQry(String Qry)
    {
        try
        {
            db=openDatabase();

            db.execSQL(Qry);
            db.close();

            return  true;
        }
            catch (Exception e )
            {

                MakeDataFile("error" ,Qry+"~~" +e.getMessage());
                return  false;
            }
    }
    public void MakeDataFile(String FileName, String Data)
    {



        File logFile = new File(Environment.getExternalStorageDirectory()+ "/" +FileName +".Log");
        if (!logFile.exists())
        {
            try
            {
                logFile.createNewFile();
            }
            catch (IOException e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        try
        {
            //BufferedWriter for performance, true to set append to file flag
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            buf.append(Data);
            buf.newLine();
            buf.close();
        }
        catch (IOException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private SQLiteDatabase openDatabase() {
        SQLiteDatabase db;
        try {

            Context applicationContext = context.getApplicationContext();
            String DB_PATH =  applicationContext.getFilesDir().getParent();
            // Create the database if it does not exist yet.
            java.lang.Thread.sleep(5);
            db = SQLiteDatabase.openOrCreateDatabase(DB_PATH + "/" + DATABASE_NAME, null, null);
            return db;
        }
        catch (Exception e)
        {
            String S= e.getMessage();
            return  null;

        }
    }

    public Cursor GetData( String Sql)
    {
        Cursor c =null;
        //Select query
        try {

            db = openDatabase();
            // db=context.openOrCreateDatabase(DATABASE_NAME, SQLiteDatabase.CREATE_IF_NECESSARY, null);

             c = db.rawQuery(Sql, null);
           int i=  c.getCount();
            return c;
        }
        catch (Exception ex) {
            String Msg= ex.getMessage();
            return c;
        }

    }

    public String GetUserRole(String UserId)
    {
        try {
            String Role = "";
            String Sql = "Select Userid,Password,Role from Login where Userid='" + UserId + "'";
            Cursor c = GetData(Sql);
            if (c.moveToFirst()) {
                do {
                    Role = c.getString(c.getColumnIndex("Role"));

                } while (c.moveToNext());

            }
            return Role;
        }
        catch (Exception ex) {
                return "";
        }


    }

    public String  onCreate() {
        try {

            db=openDatabase();
           // db=context.openOrCreateDatabase(DATABASE_NAME, SQLiteDatabase.CREATE_IF_NECESSARY, null);

            Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);

            if (c.moveToFirst()) {
                while ( !c.isAfterLast() ) {
                //    Toast.makeText(context, "Table Name=> "+c.getString(0), Toast.LENGTH_LONG).show();
                    c.moveToNext();
                }
            }
            //db.execSQL("drop Table MRO_Detail");
            String CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS  MRO_Detail (" +
                    " PoleNo  TEXT ," +
                    " ConsumerNo  INTEGER NOT NULL ," +
                    " MeterNo TEXT ," +
                    " Register INTEGER NOT NULL , " +
                    " Name TEXT, " +
                    " Co TEXT , " +
                    " HouseNo REAL , " +
                    " Street TEXT, " +
                    " City TEXT, " +
                    " ExpectedReading NUMERIC," +
                    " tolerance INTEGER, " +
                    " CurrentReading NUMERIC," +
                    " PR_MR_Date TEXT," +
                    " Sch_MR_Date TEXT," +
                    " Portion TEXT," +
                    " Pre_Decimal INTEGER," +
                    " Post_Decimal INTEGER," +
                    " Unit TEXT, " +
                    " EntryDate TEXT, " +
                    " UserId TEXT, " +
                    " MRID TEXT, " +
                    " FileName TEXT, " +
                    " Mrroute TEXT, " +
                    " Ablbelnr TEXT, " +
                    " ExpectedReading1 NUMERIC," +
                    " Billmonthyear TEXT, " +
                    " LastPmt NUMERIC, " +
                    " LastPmtDate TEXT, " +
                    " LastBillAmt NUMERIC, " +
                    " Status TEXT, " +
                    " Arrear NUMERIC, " +
                    " MobileNo TEXT, " +
                    " TabId TEXT, " +
                    " Serge TEXT, " +
                    " Ableinh TEXT, " +
                    " RateCat text, " +
                    " ISDLMS text, " +
                    " Last_Read_Data text, " +
                    " LAST_Date_Date text, " +
                    " PRIMARY KEY(ConsumerNo,Register) " +
                    ");";
            db.execSQL(CREATE_CONTACTS_TABLE);
            CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS Note_type ( "+
            " NoteId	TEXT NOT NULL, "+
            " Desc	TEXT, "+
            " PRIMARY KEY(NoteId)); );";

            db.execSQL(CREATE_CONTACTS_TABLE);


          //  CREATE_CONTACTS_TABLE = "drop TABLE  MR_Detail";
          //  db.execSQL(CREATE_CONTACTS_TABLE);

            CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS  MR_Detail ( "+
                    " ConsumerNo 	INTEGER, "+
                    " GPSCoordinate	TEXT, "+
                    " MRDateTime	TEXT, "+
                    " Reg1	NUMERIC, "+
                    " Reg2	NUMERIC,  "+
                    " Reg3	NUMERIC,  "+
                    " Reg4	NUMERIC,  "+
                    " PhotoID	TEXT,  "+
                    " IsTransfer 	TEXT, "+
                    " IsPhototransfer	TEXT, "+
                    " TransferDateTime	INTEGER, "+
                    " UserID	TEXT,  "+
                    " EntryDate	TEXT, "+
                    " NoteType	TEXT, "+
                    " NewMeterNo	TEXT ,"+
                    " MobileNo	TEXT ,"+
                    " DataMode	TEXT ," +
                    " FileName TEXT );";
            db.execSQL(CREATE_CONTACTS_TABLE);


            CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS TheftInfo (" +
                    " 	ConsumerNo TEXT," +
                    " 	MRDateTime TEXT," +
                    " 	PhotoPath TEXT," +
                    " 	EntryDate TEXT," +
                    " 	UserId  TEXT " +
                    " )";

            db.execSQL(CREATE_CONTACTS_TABLE);

            CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS Consumer_Add_Info (" +
                    " 	CONSUMERNO TEXT," +
                    " 	BILLMONTHYEAR TEXT," +
                    " 	MCB TEXT," +
                    " 	METER_LOC TEXT," +
                    " 	VCP_ID  TEXT, " +
                    " 	IsUpload  TEXT " +
                    " )";

            db.execSQL(CREATE_CONTACTS_TABLE);

            CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS PaymentData (" +
                    " 	ConsumerNo TEXT," +
                    " 	MeterNo TEXT," +
                    " 	PoleNo TEXT," +
                    " 	Name TEXT," +
                    " 	Co TEXT," +
                    " 	HouseNo TEXT," +
                    " 	Street TEXT," +
                    " 	City TEXT," +
                    " 	Billmonthyear TEXT," +
                    " 	Portion TEXT," +
                    " 	LastPmt TEXT," +
                    " 	LastPmtDate TEXT," +
                    " 	LastBillAmt TEXT," +
                    " 	TotalAmt TEXT," +
                    " 	Status TEXT," +
                    " 	Arrear TEXT," +
                    " 	MobileNo TEXT," +
                    " 	TabId TEXT," +
                    " 	EntryDate TEXT," +
                    " 	UserId  TEXT ," +
                    " 	PRIMARY KEY(ConsumerNo)" +
                    " )";
            db.execSQL(CREATE_CONTACTS_TABLE);
/* *******/
 // db.execSQL(CREATE_CONTACTS_TABLE);
            CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS ReadingLog (" +
                    " 	MeterNo TEXT," +
                    " 	MeterMake TEXT," +
                    " 	StartTime TEXT," +
                    " 	Status TEXT," +
                    " 	EndTime TEXT," +
                    " 	UserId  TEXT ," +
                    " 	FileName  TEXT ," +
                    " 	LATILONG  TEXT ," +
                    " 	IsUploaded   TEXT DEFAULT 'N' " +
                    " )";
            db.execSQL(CREATE_CONTACTS_TABLE);
       //     CREATE_CONTACTS_TABLE = "alter table ReadingLog add  LATILONG TEXT";
         //   db.execSQL(CREATE_CONTACTS_TABLE);

            CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS Addhocreading (" +
                    " 	FileName TEXT," +
                    " 	IsUploaded   TEXT DEFAULT 'N' " +
                    " )";
            db.execSQL(CREATE_CONTACTS_TABLE);




            CREATE_CONTACTS_TABLE = " CREATE TABLE IF NOT EXISTS Login " +
                    " ( " +
                    " Userid	TEXT NOT NULL, " +
                    " Password	TEXT NOT NULL, " +
                    " IsActive	TEXT DEFAULT 'Y',  " +
                    " Entrydate	TEXT,  " +
                    " Role	TEXT,      " +
                    " IEMINO	TEXT,  " +
                    " PRIMARY KEY(Userid) " +
                    " )";
            db.execSQL(CREATE_CONTACTS_TABLE);

            CREATE_CONTACTS_TABLE = " CREATE TABLE IF NOT EXISTS PaymentDetails (" +
                    " 	ConsumerNo	TEXT," +
                    " 	ContractAc	TEXT," +
                    " 	ContractNo	TEXT," +
                    " 	PaymentMode	TEXT," +
                    " 	ChequeNo	TEXT," +
                    " 	ChequeDate	TEXT," +
                    " 	PayableAmount	REAL," +
                    " 	AmountPaid	REAL," +
                    " 	PaymentDate	TEXT," +
                    " 	TabId	TEXT," +
                    " 	UserId	TEXT," +
                    " 	ReceiptNo	TEXT," +
                    " 	UploadFlag	INTEGER," +
                    " 	MobileNO	TEXT," +
                    " 	TransferDateTime	INTEGER," +
                    " 	NoOfReceipt	INTEGER," +
                    "   TID text, "+
                    "   CardPStatus text, "+
                    "   PaymentResult text, "+
                    "   AuthNumber text, "+
                    "   TXNID text  "+
                    " );";
            db.execSQL(CREATE_CONTACTS_TABLE);

           // CREATE_CONTACTS_TABLE = " drop TABLE MRO_Detail_Supervisor";
            //db.execSQL(CREATE_CONTACTS_TABLE);

            CREATE_CONTACTS_TABLE = " CREATE TABLE IF NOT EXISTS MRO_Detail_Supervisor (" +
                    " PoleNo	TEXT, " +
                    " ConsumerNo	INTEGER NOT NULL, "+
                    " MeterNo	TEXT, "+
                    " Name	TEXT, "+
                    " Co	TEXT, "+
                    " HouseNo	REAL, "+
                    " Street	TEXT, "+
                    " City	TEXT, "+
                    " ExpectedReading	INTEGER, "+
                    " tolerance	INTEGER, "+
                    " CurrentReading	NUMERIC, "+
                    " PR_MR_Date	TEXT, "+
                    " Sch_MR_Date	TEXT, "+
                    " Portion	TEXT, "+
                    " Pre_Decimal	INTEGER,"+
                    " Post_Decimal	INTEGER, "+
                    " Unit	TEXT, "+
                    " EntryDate	TEXT, "+
                    " UserId	TEXT, "+
                    " MRID	TEXT, "+
                    " FileName	TEXT, "+
                    " mrdatetime	TEXT, "+
                    " mreg1	NUMERIC, "+
                    " mreg2	NUMERIC, "+
                    " mreg3	NUMERIC, "+
                    " mreg4	NUMERIC, "+
                    " photoid  TEXT,  "+
                    " notetype	TEXT, "+
                    " smrdatetime	TEXT, "+
                    " sreg1	NUMERIC, "+
                    " sreg2	NUMERIC, "+
                    " sreg3	NUMERIC, "+
                    " sreg4	NUMERIC, "+
                    " sphotoid	TEXT, "+
                    " snotetype	TEXT, "+
                    " PremisesType	TEXT, "+
                    " NoofFloor	INTEGER, "+
                    " NoofShop	INTEGER, "+
                    " NoofRoom	INTEGER, "+
                    " Area	INTEGER, "+
                    " OpenHr	INTEGER, "+
                    " NoofAC	INTEGER, "+
                    " SnewMeterNo	TEXT, "+
                    " IsTransfer	TEXT default 'N', "+
                    " TransferDateTime	TEXT, "+
                    " ExpectedReading1	NUMERIC, "+
                    " Ablbelnr	TEXT, "+
                    " Ableinh	TEXT, "+
                    " TabId	TEXT, "+
                    " Billmonthyear	TEXT, "+
                    " Ablhinw TEXT, "+
                    " DataMode TEXT, "+
                    " GpsCoordinate TEXT, "+
                    " Serge	TEXT  );";
            db.execSQL(CREATE_CONTACTS_TABLE);


            //db.execSQL("Drop table Reading_Remarks");

            CREATE_CONTACTS_TABLE = "CREATE TABLE IF NOT EXISTS Reading_Remarks (" +
                    "Meter_number TEXT," +
                    "Created_on TEXT," +
                    "Consumer_number TEXT," +
                    "VCP_ID TEXT," +
                    "Main_mr_code TEXT," +
                    "Sub_remarks TEXT," +
                    "MRMODE TEXT," +
                    "IsTransfer TEXT DEFAULT 'N'" +
                    ");";

            db.execSQL(CREATE_CONTACTS_TABLE);

            return "Table Created";
        }
        catch (Exception ex) {
            return ex.getMessage();
        }
        }
    public String[][]  GetDataTable(String Qry, int NoofCols)
    {
        String arrData[][] = null;
        try
        {
            db=openDatabase();
            Cursor cursor = db.rawQuery(Qry, null);
            if(cursor != null)
            {

                if (cursor.moveToFirst()) {
                    arrData = new String[cursor.getCount()][cursor.getColumnCount()];

                    int i= 0;
                    do {
                        for (int j=0; j< NoofCols;j++) {
                            arrData[i][j] = cursor.getString(j);
                        }
                        i++;

                    } while (cursor.moveToNext());
                }}

            cursor.close();
            return  arrData;
        }
         catch ( Exception e)
         {
             return arrData;

         }


    }


    }


