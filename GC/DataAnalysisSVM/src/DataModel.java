/**
 * Created by Lijie on 2017/5/31.
 */
public class DataModel {

    String AppName;
    double dmax;
    double dmin;
    double db;
    double ds;
    double dmedian;

    public String getAppName() {
        return AppName;
    }

    public void setAppName(String appName) {
        AppName = appName;
    }

    public double getDb() {
        return db;
    }

    public void setDb(double db) {
        this.db = db;
    }

    public double getDmax() {
        return dmax;
    }

    public void setDmax(double dmax) {
        this.dmax = dmax;
    }

    public double getDmedian() {
        return dmedian;
    }

    public void setDmedian(double dmedian) {
        this.dmedian = dmedian;
    }

    public double getDmin() {
        return dmin;
    }

    public void setDmin(double dmin) {
        this.dmin = dmin;
    }

    public double getDs() {
        return ds;
    }

    public void setDs(double ds) {
        this.ds = ds;
    }

    @Override
    public String toString() {
        return super.toString();
    }
}
