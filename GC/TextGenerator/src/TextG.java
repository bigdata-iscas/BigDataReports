import java.io.*;
import java.util.Random;

/**
 * Created by Lijie on 2017/5/30.
 */
public class TextG {

    public static void main(String args[]) throws IOException {

        String[] array = new String[1000];
        String str;
        int i=0;
        FileReader word = new FileReader("word.txt");
        BufferedReader br = new BufferedReader(word);
        while((str = br.readLine()) != null){
            if(str!="\r")
            {
                array[i] = str;
                i++;
            }

        }
        FileWriter file=new FileWriter("file.txt");

        FileOutputStream fs =new FileOutputStream(new File("word3.txt"));
        PrintStream p = new PrintStream(fs);
      //  BufferedWriter bf=new BufferedWriter(new PrintWriter(file));
        int cnt ;
        for(cnt = 0; cnt<999999999;cnt++)
        {
 /*           //PrintStream p = new PrintStream(file);
            Random random = new Random();
            int ran = random.nextInt(5);
            System.out.println(array[ran]);
      //      bf.append(array[ran]);
            int ran1 = random.nextInt(5);
            System.out.println(array[ran1]);
            file.write(array[ran]+" "+array[ran1]);
            file.write("\r\n");
          //  bf.append(array[ran1]);
            //p.println(array[ran] + " " + array[ran1]);*/

            StringBuffer sb = new StringBuffer();
            for (int m = 0; m <3; m++) {
                Random random = new Random();
                int ran = random.nextInt(100);
                System.out.println(array[ran]);
                sb.append(array[ran]);
                sb.append(" ");
            }
            System.out.println(sb);
            p.println(sb);

        }










    }



}
