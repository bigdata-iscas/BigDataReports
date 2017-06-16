/**
 * Created by Lijie on 2017/5/31.
 */

import jxl.Sheet;
import jxl.Workbook;
import jxl.read.biff.BiffException;
import jxl.write.Label;
import jxl.write.WritableSheet;
import jxl.write.WritableWorkbook;
import jxl.write.WriteException;
import jxl.write.biff.RowsExceededException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static java.awt.SystemColor.info;

public class DataAnalysis {
    public static void main(String[] args) throws IOException, WriteException {

        String[] array = new String[1000];
        String str;
        int m=0;
        FileReader word = new FileReader("data.txt");
        BufferedReader br = new BufferedReader(word);
        while((str = br.readLine()) != null){
            if(str!="\r")
            {
                array[m] = str;
                m++;
            }
       }

        List<DataModel> data = new ArrayList<DataModel>();

        List<DataModelGC> gcdata = new ArrayList<DataModelGC>();
       for(int datacnt=0;datacnt<27;datacnt++)
       {

           // 1、构造excel文件输入流对象
           String sFilePath = "C:\\data\\ali\\PageRank\\"+array[datacnt]+"Task.xls";
           InputStream is = null;
           try {
               is = new FileInputStream(sFilePath);
           } catch (FileNotFoundException e) {
               e.printStackTrace();
           }
           // 2、声明工作簿对象
           Workbook rwb = null;
           try {
               rwb = Workbook.getWorkbook(is);
           } catch (IOException e) {
               e.printStackTrace();
           } catch (BiffException e) {
               e.printStackTrace();
           }
           // 3、获得工作簿的个数,对应于一个excel中的工作表个数
           rwb.getNumberOfSheets();

           Sheet oFirstSheet = rwb.getSheet(0);// 使用索引形式获取第一个工作表，也可以使用rwb.getSheet(sheetName);其中sheetName表示的是工作表的名称
//        System.out.println("工作表名称：" + oFirstSheet.getName());

           int num;
           int start = 34;
           int end = 353;
           num = end-start+1;

           double[] duration = new double[num];
           double[] gc = new double[num];

           double dmax;
           double dmedian;
           double ds;
           double db;
           double dmin;

           double gmax;
           double gmedian;
           double gs;
           double gb;
           double gmin;



           int i;
           for(i=start-1;i<=end-1;i++)
           {
               int tmp1,tmp2;
               tmp1 =i+1-start;

               duration[tmp1] =Double.valueOf(oFirstSheet.getCell(5,i).getContents().trim());
               gc[tmp1] = Double.valueOf(oFirstSheet.getCell(4,i).getContents().trim());

           }
           for(i=0;i<num;i++)
           {
               System.out.println("Duration:" + duration[i]);

           }


           //    System.out.println("数据预处理时间是："+ preProcess);

           double temp = 0.0;
           for(i=duration.length-1;i>0;--i)
           {
               for(int j = 0;j<i;++j)
               {
                   if(duration[j+1]< duration[j])
                   {
                       temp = duration[j];
                       duration[j] = duration[j+1];
                       duration[j+1] = temp;
                   }
                   if(gc[j+1]<gc[j])
                   {
                       temp = gc[j];
                       gc[j] = gc[j+1];
                       gc[j+1] = temp;
                   }
               }
           }

           dmax=duration[num-1];
           dmin=duration[0];
           dmedian=duration[(num)/2-1];
           db=duration[(num*3)/4-1];
           ds=duration[(num)/4-1];

           DataModel dataModel = new DataModel();
           dataModel.setAppName(array[datacnt]);
           dataModel.setDmax(duration[num-1]);
           dataModel.setDmin(duration[0]);
           dataModel.setDb(duration[(num*3)/4-1]);
           dataModel.setDs(duration[(num)/4-1]);
           dataModel.setDmedian(duration[(num)/2-1]);
           data.add(dataModel);

           DataModelGC dataModelGC = new DataModelGC();
           dataModelGC.setAppName(array[datacnt]);
           dataModelGC.setGmax(gc[num-1]);
           dataModelGC.setGmin(gc[0]);
           dataModelGC.setGb(gc[(num*3)/4-1]);
           dataModelGC.setGs(gc[(num)/4-1]);
           dataModelGC.setGmedian(gc[(num)/2-1]);
           gcdata.add(dataModelGC);



       }


        String path="E:\\DataAnalysis\\data.xls" ;
        WritableWorkbook book = null;
        try {
            book = Workbook.createWorkbook(new File(path));
        } catch (IOException e) {
            e.printStackTrace();
        }



        WritableSheet sheet = book.createSheet("Duration",0);
        Label label = new Label(0, 0, "App");
        Label label1 = new Label(1, 0, "Min/ms");
        Label label2 = new Label(2, 0, "25th percentile/ms");
        Label label3 = new Label(3, 0, "Median/ms");
        Label label4 = new Label(4, 0, "75th percentile/ms");
        Label label5 = new Label(5, 0, "Max/ms");
        sheet.addCell(label);
        sheet.addCell(label1);
        sheet.addCell(label2);
        sheet.addCell(label3);
        sheet.addCell(label4);
        sheet.addCell(label5);

        WritableSheet sheet1 = book.createSheet("GC",1);
        Label l = new Label(0, 0, "App");
        Label l1 = new Label(1, 0, "Min/ms");
        Label l2 = new Label(2, 0, "25th percentile/ms");
        Label l3 = new Label(3, 0, "Median/ms");
        Label l4 = new Label(4, 0, "75th percentile/ms");
        Label l5 = new Label(5, 0, "Max/ms");
        sheet1.addCell(l);
        sheet1.addCell(l1);
        sheet1.addCell(l2);
        sheet1.addCell(l3);
        sheet1.addCell(l4);
        sheet1.addCell(l5);
        int cnt;
        cnt = data.size();
        for(m=0;m<cnt;m++)
        {
            Label labell = new Label(0, m+1, data.get(m).getAppName());
            jxl.write.Number number = new jxl.write.Number(1,m+1,data.get(m).getDmin());
            jxl.write.Number number1 = new jxl.write.Number(2,m+1,data.get(m).getDs());
            jxl.write.Number number2 = new jxl.write.Number(3,m+1,data.get(m).getDmedian());
            jxl.write.Number number3 = new jxl.write.Number(4,m+1,data.get(m).getDb());
            jxl.write.Number number4 = new jxl.write.Number(5,m+1,data.get(m).getDmax());

            sheet.addCell(labell);
            sheet.addCell(number);
            sheet.addCell(number1);
            sheet.addCell(number2);
            sheet.addCell(number3);
            sheet.addCell(number4);
            Label x = new Label(0, m+1, gcdata.get(m).getAppName());
            jxl.write.Number number11 = new jxl.write.Number(1,m+1,gcdata.get(m).getGmin());
            jxl.write.Number number12 = new jxl.write.Number(2,m+1,gcdata.get(m).getGs());
            jxl.write.Number number13 = new jxl.write.Number(3,m+1,gcdata.get(m).getGmedian());
            jxl.write.Number number14 = new jxl.write.Number(4,m+1,gcdata.get(m).getGb());
            jxl.write.Number number15 = new jxl.write.Number(5,m+1,gcdata.get(m).getGmax());

            sheet1.addCell(x);
            sheet1.addCell(number11);
            sheet1.addCell(number12);
            sheet1.addCell(number13);
            sheet1.addCell(number14);
            sheet1.addCell(number15);

       }

        book.write();
        book.close();



    }




}


