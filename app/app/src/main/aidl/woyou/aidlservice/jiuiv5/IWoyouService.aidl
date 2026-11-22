package woyou.aidlservice.jiuiv5;

import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.ITax;
import com.sunmi.trans.TransBean;
import android.graphics.Bitmap;

// Binder transaction codes follow declaration order, so this order must match the
// printer service installed on the device (SunmiPrinterService 4.4.1 on the V2).
// It differs from the IWoyouService.aidl in Sunmi's public printer library.
interface IWoyouService {
    boolean postPrintData(String tag, in byte[] data, int offset, int length);
    int getFirmwareStatus();
    String getServiceVersion();
    void printerInit(ICallback callback);
    void printerSelfChecking(ICallback callback);
    String getPrinterSerialNo();
    String getPrinterVersion();
    String getPrinterModal();
    void getPrintedLength(ICallback callback);
    void lineWrap(int n, ICallback callback);
    void sendRAWData(in byte[] data, ICallback callback);
    void setAlignment(int alignment, ICallback callback);
    void setFontName(String typeface, ICallback callback);
    void setFontSize(float fontsize, ICallback callback);
    void printText(String text, ICallback callback);
    void printTextWithFont(String text, String typeface, float fontsize, ICallback callback);
    void printColumnsText(in String[] texts, in int[] widths, in int[] aligns, ICallback callback);
    void printBitmap(in Bitmap bitmap, ICallback callback);
    void printBarCode(String data, int symbology, int height, int width, int textPosition, ICallback callback);
    void printQRCode(String data, int moduleSize, int errorLevel, ICallback callback);
    void printOriginalText(String text, ICallback callback);
    void commitPrint(in TransBean[] beans, ICallback callback);
    void commitPrinterBuffer();
    void enterPrinterBuffer(boolean clean);
    void exitPrinterBuffer(boolean commit);
    void tax(in byte[] data, ITax callback);
    void getPrinterFactory(ICallback callback);
    void clearBuffer();
    void commitPrinterBufferWithCallback(ICallback callback);
    void exitPrinterBufferWithCallback(boolean commit, ICallback callback);
    void printColumnsString(in String[] texts, in int[] widths, in int[] aligns, ICallback callback);
    int updatePrinterState();
    void printBitmapCustom(in Bitmap bitmap, int type, ICallback callback);
    int getForcedDouble();
    boolean isForcedAntiWhite();
    boolean isForcedBold();
    boolean isForcedUnderline();
    int getForcedRowHeight();
    int getFontName();
    int getPrinterDensity();
    void print2DCode(String data, int symbology, int moduleSize, int errorLevel, ICallback callback);
    int getPrinterPaper();
    void autoOutPaper(ICallback callback);
    void setPrinterStyle(int key, int value);
}
