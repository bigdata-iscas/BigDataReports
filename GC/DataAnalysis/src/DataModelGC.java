/**
 * Created by Lijie on 2017/5/31.
 */
public class DataModelGC {


    String AppName;
    double gmax;
    double gmin;
    double gb;
    double gs;
    double gmedian;

    public void setAppName(String appName) {
        AppName = appName;
    }

    public String getAppName() {
        return AppName;
    }

    public double getGb() {
        return gb;
    }

    public double getGmax() {
        return gmax;
    }

    public double getGmedian() {
        return gmedian;
    }

    public double getGmin() {
        return gmin;
    }

    public double getGs() {
        return gs;
    }

    public void setGb(double gb) {
        this.gb = gb;
    }

    public void setGmax(double gmax) {
        this.gmax = gmax;
    }

    public void setGmedian(double gmedian) {
        this.gmedian = gmedian;
    }

    public void setGmin(double gmin) {
        this.gmin = gmin;
    }

    public void setGs(double gs) {
        this.gs = gs;
    }

    @Override
    public String toString() {
        return super.toString();
    }
}
