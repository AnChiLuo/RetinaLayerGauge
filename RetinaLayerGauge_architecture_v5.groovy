// =============================================================================
// [IMPORTS]
// =============================================================================
/*
2026-04-30 版本備忘
我在做的事：
- 從一條龍 macro，重構成有 PipelineContext + ImageTracker 的系統。
- 第一段：自動核分割 + gap 幾何修補 → 產生 final mask。
- 第二段：GUI（Brush + Wand + ROI List）讓使用者手動 refine mask。
- 第三段: 物件骨幹萃取+ 中心線提取+ 有效骨幹驗證 已經完成
- 第四段: 厚度量測 綁入 objectItem 內已經完成
- 第五段: 重塑 tracker 的管理邏輯至通用版本(IJ.run， def function ，API)(Done)
- 第六段: cleanup Imp based on life-cycle and white list(Done)
- 第七段: CCL map 與 自動存檔 done


目前進度：
-所有階段已經接合並完成測試

下一步如果要繼續：
清除註解與暫時用不到的工具
-測試不同結構影像
*/
// --- [Java & Groovy 工具] ---

import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_
import java.util.List
import java.nio.file.*
import java.util.concurrent.CountDownLatch // 關鍵：線程鎖

// --- [GUI 介面組件 (Swing/AWT)] ---
import javax.swing.*
import javax.swing.event.*
import javax.swing.border.LineBorder
import java.awt.*
import java.awt.event.*

// --- [ImageJ 核心與影像處理] ---
import ij.*
import ij.gui.*
import ij.process.*
import ij.plugin.*
import ij.plugin.filter.*
import ij.measure.ResultsTable
import ij.macro.Interpreter

// --- [ImageJ 特定框架與工具] ---
import ij.plugin.frame.RoiManager         // 記得用 RoiManager.getRoiManager()
import ij.plugin.frame.ThresholdAdjuster
import ij.plugin.tool.PlugInTool          // 給你那支很厲害的筆刷用

// =============================================================================
// [0] SCRIPT ENTRY – Application Entry Point
// =============================================================================
// 這裡只負責「故事大綱」，不要寫演算法細節。
//2026-03-10 添加 objectStore 與 objectItem 進入 GUI bottom Btn

def main() {
    Prefs.blackBackground = false
    // 1. Ask user input (UI layer) && load image
    ImagePlus ori = loadValidImage()
    if (ori == null) return
    ori.show()

    //2. Prepare core element
    PipelineContext ctx = new PipelineContext(ori)
    ImageTracker tracker = ctx.getImgTracker()
    tracker.addImp(ori)

    // 3. Ask Nucleus Diameter
    ctx.setNucleusSize(askNucleusDiameter(ori))
    ori.hide()

    // 4. Run nucleus extraction (APP layer)
    runNucleusExtraction(ctx)

    // 5. Launch GUI (UI layer)
    launchAndWaitMaskGui(ctx)

    //6. Extract Object feature and measure profile
    prepareAndAnalysisFeatures(ctx)

    //7. Data visualisation and saving
    visualiseData(ctx)

}
    main()


// =============================================================================
// [1] CORE – System Foundation & Shared State
// =============================================================================
// 共用資料結構與工具

class PipelineContext {
    private final ImagePlus rawColorImg
    private ImagePlus oriMask
    private final RoiStore roiStore = new RoiStore()
    private final ImageTracker tracker = new ImageTracker()
    private final ObjectStore  objectStore = new ObjectStore()
    private double ncuDia
    private ImagePlus refinedMask
    private boolean batchMode = true

    PipelineContext(ImagePlus colorImg){
        this.rawColorImg = colorImg
    }

    void setOriMask(ImagePlus ImpMask) {
        this.oriMask = ImpMask
    }
    void setNucleusSize(double diameter) {
        if( diameter <=0) {
            throw new IllegalArgumentException("Invalid nucleus size")
        }
        this.ncuDia = diameter
    }

    void setRefinedMask(ImagePlus refinedMask){
        this.refinedMask = refinedMask
    }

    void setCurrentObjectImp(ImagePlus currentObjectImp){
        this.currentObjectImp = currentObjectImp
    }

    RoiStore getRoiStore(){ return roiStore }
    ImageTracker getImgTracker() {return tracker}
    ImagePlus getRawColorImg() {return rawColorImg}
    ImagePlus getOriMask() {return oriMask}
    ImagePlus getRefinedMask() {return refinedMask}
    double getNcuDia(){return ncuDia}
    ObjectStore getObjectStore() {return objectStore}
    boolean getBatchMode() {return batchMode}
}
/**
 *ImageTracker: 影像生命週期管理與副作用追蹤器
 * Purpose :
 * 追蹤 ImagePlus(Imp) 的生成與釋放，避免由 Plugins、IJ.run 或自定義函式產生的中間的未受控影像，造成記憶體洩漏（Memory Leak）。
 * Design :
 * 透過 runAndReturn() 偵測新 Imp 物件，以暫存性質 (Trash) 註冊至追蹤集合(track)，可於流程結束後透過 cleanup 系列方法清理。
 *
 * Engineering insight:
 * 將隱性輸出影像(implicit side-effects) 轉為可追蹤的資料流(explicit data flow)，提升 pipeline 的可預測性與穩定性。
 *
 * Note:
 *   - cleanup 系列方法提供不同策略的資源釋放，cleanupByLifeCycle() 為主要清理方法。
 *   - 適用於混合 pipeline（Processor + IJ.run）
 */
class ImageTracker{
    // ===== Constants =====
    static final String KEY = "lifeCycle"
    static final String TRASH = "Trash"
    static final String KEEP = "Keep"

    // =====  Fields/ Internal State =====
    private final Set<ImagePlus> track = new LinkedHashSet<>()

    // ===== Public API =====
    /**
     **執行指定的閉包（Closure）以 WindowManager 追蹤期間產生的所有 Imp 物件。
     *  Behavior::
     *  - before/after 比較 → 偵測新產生的影像
     *  - 顯式回傳 + 隱式產物 → 統一收集
     *  - 使用 property("lifeCycle") 標記資源狀態
     * @param action   封裝的分析邏輯，可包含顯式回傳。
     * @param addTrack 是否將偵測到的 Imp 納入生命週期追蹤。。
     * @return 優先回傳action 的顯式輸出；若無顯式輸出，則回傳偵測到的 Imp。
     */
    Object runAndReturn(Closure action, boolean addTrack = true){
        // 記錄執行前後的影像狀態
        def before = listOpenImp() as Set
        def created = action.call() //顯示回傳
        def after = listOpenImp() as Set
        LinkedHashSet<ImagePlus> result = new LinkedHashSet<ImagePlus>(after - before) //中產物

        // 處理 顯式回傳的物件 (支援 Map, List, 單一物件)
        def items = ( created instanceof Map)? created.values() : [created]
        result.addAll(items.flatten().grep(ImagePlus) as Collection<ImagePlus>)
        //[items].flatten().each{it -> if(it instanceof ImagePlus){result.add(it)}}
        if(addTrack){
            result.each{imp->
                TagImpAsTrash(imp)
            }
            this.track.addAll(result)
        }

        //決策邏輯
        if ( result.isEmpty() && created == null) {
            IJ.log("[ImageTracker] action created no ImagePlus")
        }
        //優先回傳 action 內容
        if (created != null){
            return created
        }
        if (result.isEmpty()) return null
        return (result.size() ==1)?  result.toList()[0] : result.toList()
    }

    //【嚴格斷言】適用於：明確只要「一張」結果。若出來 0 或多張會噴錯（防演算法失控）。
    def runAndReturnOneImp(Closure action, boolean addTrack = true){
        def created = this.runAndReturn(action, addTrack)
        if(!created){
            throw new IllegalStateException("Expected 1 new ImagePlus, got 0")
        }
        if(created.size() !=1 ){
            throw new IllegalStateException("Expected 1 new ImagePlus got ${created.size()}")

        }

        return created[-1]
    }

    /**
     **Close all tracked ImagePlus objects marked as "Trash".
     * Behavior:
     * - Only images with property("lifeCycle") == "Trash" will be closed.
     * - Items in `Except` will be preserved (accepts ImagePlus or title String).
     * - Closed images are removed from the internal track set.
     * - This operation is destructive (imp.close + imp.flush).
     *
     * @param debug   是否輸出被關閉的 ImagePlus 名稱（debug log）。
     * @param Except 保留清單（ImagePlus 或 title String）， 不影響其 lifeCycle property。
     */
    void cleanupByLifeCycle(boolean debug = false, List<Object> Except = []){
        if(!this.track.isEmpty()){
            Set<ImagePlus> closeSet = track.findAll{it -> it && it.getProperty("lifeCycle") == "Trash"}
            if (!Except.isEmpty()) {
                List<ImagePlus> finalExcepts = Except.collect { entry ->
                    (entry instanceof CharSequence) ? track.find { imp -> imp.getTitle() == entry } : entry
                } as List<ImagePlus>
                finalExcepts = finalExcepts.findAll { it instanceof ImagePlus }
                closeSet.removeAll(finalExcepts)
            }

            track.removeAll(closeSet)
            closeSet.each{imp->
                if( imp!= null ){
                    if(debug) IJ.log("Deleting ImagePlus_${imp.getTitle()}...")
                    imp.changes =false
                    imp.close()
                    imp.flush()
                }
            }
        }
    }

    void cleanupExceptII(List<Object> keeps, boolean debug = false){
        Set<ImagePlus> keepsImp = new LinkedHashSet<>()
        if(!this.track.isEmpty()){
            keeps.each{ obj ->
                if(obj instanceof ImagePlus) { keepsImp.add(obj)}
                else if(obj instanceof CharSequence) {
                    def imp = this.track.findAll{it -> it.getTitle() == obj.toString()}
                    keepsImp.addAll(imp)
                }
            }
            if(keepsImp.isEmpty()) { return }
            Set<ImagePlus> closedImp = this.track - keepsImp
            List<String> closed = closedImp.collect{imp -> imp.getTitle()}
            this.track.removeAll(closedImp)
            closedImp.each{ImagePlus imp ->
                if(imp.getWindow() != null){
                    imp.changes = false
                    imp.close()
                }
                imp.flush()
            }
            if (debug) {
                println("Close ${closed.join(", ")}")
            }
        }
    }

    void cleanupExceptByName(List<String> keepImg, boolean debug = false){
        def keepImgSet = keepImg as Set
        Set<ImagePlus> closedImp = this.track.findAll{ImagePlus it -> ! keepImgSet.contains(it.getTitle())}
        if(closedImp.isEmpty()){
            IJ.log("lacking keepImages")
            return}
        this.track.removeAll(closedImp)
        closedImp.each { ImagePlus imp ->
            if (imp.getWindow() != null) {
                imp.changes = false
                imp.close()
            }
            imp.flush()
        }
        if(debug){
            List<String> closeList = closedImp.collect{ImagePlus it -> it.getTitle()}
            IJ.log("Close ${closeList.join(", ")}")
        }
    }

    // ===== Public Helper =====
    /**
     * Manually register ImagePlus in Track
     */
    void addImp (ImagePlus imp){
        if( imp!= null) {
            TagImpAsTrash(imp)
            this.track.add(imp)
        }
    }

    static ImagePlus pickBySuffix(List<ImagePlus> Imps, String suffix){
        return (Imps.find {it -> it.getTitle()?.endsWith(suffix)})

    }

    List<ImagePlus> getTrackedImages() {
        return new ArrayList<>(track)
    }

    // ===== Internal Helper =====
    private static List<ImagePlus> listOpenImp(){
        int[] ImpIds = WindowManager.getIDList()
        if( ImpIds == null || ImpIds.length == 0 ){return []}
        ImpIds.collect{ it -> WindowManager.getImage(it)}.findAll{ it -> it != null}
    }

    private void TagImpAsTrash(ImagePlus imp){
        if(imp != null && imp.getProperty(KEY) == null){
            imp.setProperty(KEY, TRASH)
        }
    }
}

/**
 *RoiItem: 單一 ROI 的資料逐存容器
 * Purpose:
 * 儲存 ROI、label、component ID，以及用於 GUI 階段，輔助 overlay 顯示的工具。
 */

class RoiItem {
    Roi roi
    String label
    int componentID
    private TextRoi labelRoi
    Roi skeletonRoi = null
    Roi skeletonRoi_Rf = null

    TextRoi getTextRoi(){labelRoi}

    void createLabelRoi(){
        if(roi ==null || label == null){
            labelRoi = null
            return
        }
        double [] centriod = roi.getContourCentroid()
        TextRoi t = new TextRoi(centriod[0], centriod[1], label, new Font("Microsoft JhengHei", Font.BOLD, 24) )
        t.setStrokeColor(new Color(0, 220, 255))
        t.setFillColor(new Color(0, 0, 0, 200))
        this.labelRoi = t
    }
    String toString() {label}
}
/**
 * RoiStore: 管理 RoiItem 集合與其對應的 UI list。
 *
 * Note:
 * - 同步維護資料（finalObjs）與 UI model（objectLst）。
 * - 在 human in loop 階段中同步更新 新增與刪除操作，確保一致性。
 */
class RoiStore {
    private final List<RoiItem> finalObjs = new ArrayList<>()
    private final DefaultListModel<RoiItem> objectLst = new DefaultListModel()

    void addRoi(RoiItem objectRoi){
        this.objectLst.addElement(objectRoi)
        this.finalObjs.add(objectRoi)
    }

    void deleteRoi(int indx){
        this.objectLst.removeElementAt(indx)
        this.finalObjs.remove(indx)
    }

    boolean isEmpty(){return finalObjs.isEmpty()}
    List<RoiItem> getFinalObjs(){return finalObjs}
    DefaultListModel<RoiItem> getObjectLst() {return objectLst}
    List<Roi> getRois(){return finalObjs.collect {RoiItem it -> it.roi} }
    List<Roi> getRfSkeletonRois(){return finalObjs.collect {RoiItem it -> it.skeletonRoi_Rf} }
    List<Roi> getSkeletonRois(){return finalObjs.collect {RoiItem it -> it.skeletonRoi} }
    List<TextRoi> getRoiLabel(){return finalObjs.collect {RoiItem it -> it.getTextRoi()} }
}

/**
 * ObjectItem: 物件資料容器
 * Purpose:
 * 管理單一物件的中間資料與量測結果，包括 object ROI、object mask、
 * mainChain、refinedMainChain、object EDM、thickness profile 與統計結果。
 *
 * Note:
 * -本類別不負責由 EDM 產生 thickness profile；該步驟應由分析模組處理。
 * -getThicknessAnalysis() 僅負責對既有 thicknessProfile 進行統計分析 ，
 * 包含基本厚度統計與品質指標，例如 mean、min、max、stdDev、CV、 breakRate 與 roughness。
 */
class ObjectItem {
    Roi objectRoi
    ImageProcessor objectMask = null
    ArrayList<Point> mainChain
    ArrayList<Point> refinedMainChain
    FloatProcessor objEDM
    float [] thicknessProfile
    Map tkAnalysisResult

    ObjectItem(Roi objectRoi) {
        this.objectRoi = objectRoi
    }

    void setObjEDM (FloatProcessor FP){
        this.objEDM = FP
    }
    /**
     * Analyze an existing thickness Profile
     * @param ncuDia minimum acceptable structural scale based on nucleus size
     * @param scale pixel-to-physical conversion factor (e.g., μm per pixel)
     * @param unit physical unit of of the output measurements
     * @return thickness statistics and quality metrics
     */
    Map<String, Double> getThicknessAnalysis(double ncuDia, double scale = 1.0, String unit = "px"){
        String mean = "mean(${unit})"
        String min = "min(${unit})"
        String max = "max(${unit})"
        String stdDev = "stdDev(${unit})"

        if (! thicknessProfile) {
            return [ (mean): Double.NaN, (min): Double.NaN, (max): Double.NaN, (stdDev): Double.NaN,
                     "breakRate": Double.NaN, "CV": Double.NaN, "roughness": Double.NaN]
        }

        FloatProcessor tempFp = new FloatProcessor(thicknessProfile.length, 1, thicknessProfile)
        tempFp.multiply(scale)

        FloatStatistics stat = new FloatStatistics(tempFp)

        // --- 1. 局部斷裂偵測 (Local Breaks) ---
        // 計算有多少像素低於一個細胞核的厚度
        int breakCount = thicknessProfile.count{it < ncuDia }
        double breakRate = (breakCount/thicknessProfile.length) *100

        // ---2. 變異係數 (cv) ---
        float cv = (stat.mean >0) ? (stat.stdDev / stat.mean) : 0

        // ---3. 厚度變化率 (Roughness) ---
        float totalDiff = 0
        for (int i = 0; i <thicknessProfile.length - 1; i++){
            totalDiff += Math.abs(thicknessProfile[i+1]-thicknessProfile[i])
        }
        double roughness = totalDiff/(thicknessProfile.length-1)
        return [ (mean): stat.mean, (min): stat.min, (max): stat.max, (stdDev): stat.stdDev, "breakRate": breakRate, "CV": cv, "roughness": roughness]
    }

}
class ObjectStore {
    private final List<ObjectItem> objList = new ArrayList<>()

    void addObject(ObjectItem obj){
        this.objList.add(obj)
    }

    void deleteObject (int Index){
        this.objList.remove(Index)
    }
    boolean isEmpty(){return objList.isEmpty()}
    List<ObjectItem> getAll(){return objList}

}

// =============================================================================
// [2] ENGINE – Algorithm Modules (Reusable Steps)
// =============================================================================
//
// -----------------------------------------------------------------------------
// [2-1] Part1 – Mask Building
// -----------------------------------------------------------------------------
/**
 * NucleiChannelExtractor: 從 color image 建立 nuclei-based structure mask
 * NucleusExtractorOps：提供 nuclei channel extraction 與 mask 建立的底層操作(純演算法層)。
 *
 * Purpose:
 * 從 H&E /color image 中分離 nuclei channel, 經影像強化與 threshold 建立代表目標組織結構的 nuclei-based mask。
 *
 * Image logic:
 * Color image ->  Colour deconvolution(get nuclei channel)
 * -> bandPass filtering + subtraction（強化 nuclei signal）
 * -> median filtering (去噪)
 * -> → manual threshold（控制結構定義）
 * -> binary mask
 */
class NucleiChannelExtractor {
    private String colorMode
    ImageTracker tracker
    boolean batchMode

    NucleiChannelExtractor (ImageTracker tracker, String colorMode = "vector=H&E hide", Boolean batchMode){
        this.tracker = tracker
        this.colorMode = colorMode
        this.batchMode = batchMode
    }

    ImagePlus run(ImagePlus Imp, double ncuDia){
        // --- Step 1: Extract Nuclei Channel from Color Image ---
        if(batchMode) {Interpreter.batchMode = true}
        List<ImagePlus> create =tracker.runAndReturn(
                {IJ.run(Imp, "Colour Deconvolution", colorMode)}
        ) as List<ImagePlus>
        ImagePlus nucleiImp = tracker.pickBySuffix(create, "(Colour_1)")
        tracker.cleanupByLifeCycle(true, [Imp, nucleiImp])

        // --- Step 2: Signal Enhancement using FFT ---
        ImagePlus enhancedNuclei = tracker.runAndReturn({
            NucleusExtractorOps.EnhanceNucleiWithFFT(nucleiImp, ncuDia)}) as ImagePlus

        if(batchMode) {Interpreter.batchMode = false}
        NucleusExtractorOps.askThreshold(enhancedNuclei, "Triangle dark")
        if(batchMode) {Interpreter.batchMode = true}

        ImagePlus nucleiMask = tracker.runAndReturn({
            NucleusExtractorOps.convertNucleiToMask(enhancedNuclei)}) as ImagePlus

        tracker.cleanupByLifeCycle(true, [Imp, nucleiMask])
        return nucleiMask
    }
}

class NucleusExtractorOps {
    static ImagePlus EnhanceNucleiWithFFT(ImagePlus dapiImp, double Diameter){
        dapiImp.getProcessor().invert()
        ImagePlus fft = dapiImp.duplicate()

        double  medianRadius = Diameter /3.0
        double lowCut = Diameter * 30
        double highCut = Diameter * 1

        IJ.run( fft, "Bandpass Filter...", "filter_large=${lowCut} filter_small=${highCut} suppress=None tolerance=5 autoscale saturate")
        fft.getProcessor().invert()

        ImagePlus enhancedNuclei = new ImageCalculator().run("Subtract create", dapiImp, fft)
        IJ.run(enhancedNuclei, "Median...","radius=${medianRadius}" )
        return enhancedNuclei
    }

    static void askThreshold(ImagePlus imp, String defaultMethod){
        ImageProcessor temp = imp.getProcessor()
        temp.setAutoThreshold(defaultMethod)
        imp.show()
        do {
            ThresholdAdjuster.setMethod(defaultMethod)
            ThresholdAdjuster.setMode("red")
            //ThresholdAdjuster manuallyAdjuster = new ThresholdAdjuster()
            Dialog dialog = new WaitForUserDialog("Manually adjust", "Fine-tune the threshold value!!")
            dialog.show()
        }
        while(!imp.isThreshold())
    }

    static ImagePlus convertNucleiToMask(ImagePlus enhancedImp, showResult = false){
        ByteProcessor maskProcessor = enhancedImp.createThresholdMask()
        ImagePlus nucleiMask = new ImagePlus("nucleiMask", maskProcessor)
        maskProcessor.invertLut()
        if(showResult){nucleiMask.show()}
        return nucleiMask
    }
}

/**
 * Extract candidate gap regions from structure mask.
 *
 * Purpose:
 * Identify discontinuous or weakly connected regions
 * that are likely caused by sparse nuclei distribution.
 */
class ExtractGapRegionStep {
    ImageTracker tracker

    ExtractGapRegionStep (ImageTracker tracker){
        this.tracker = tracker
    }

    ImagePlus run(ImagePlus Imp, double Diameter){
        IJ.run(Imp, "Fill Holes", "")
        ImagePlus Gap_distance = tracker.runAndReturn({Imp.duplicate()}) as ImagePlus
        Gap_distance.getProcessor().invert()
        ImagePlus LocThk = tracker.runAndReturn({  IJ.run(Gap_distance, "Local Thickness (masked, calibrated, silent)", "")}) as ImagePlus
        LocThk.getProcessor().setThreshold(0, Diameter*2)
        def maskProcessor = LocThk.createThresholdMask()
        LocThk.setProcessor("Gap_distance_LocThk", maskProcessor)
        maskProcessor.invertLut()
        return LocThk
    }
}

/**
 *  Reconstruct connectivity across candidate gap regions.
 *
 * Strategy:
 * This step combines two complementary reconnection hypotheses:
 *
 * 1. Erode-based connectivity:
 *    conservative reconstruction.
 *
 * 2. Skeleton-based connectivity:
 *    centerline-guided reconstruction.
 *
 * 3. EDM-weighted fusion:
 *    distance-map blending of both reconstruction masks.
 *
 * Purpose:
 * Repair segmentation gaps caused by sparse or fragmented nuclei distribution
 * while preserving the overall structural geometry.
 *
 */
class GapConnectivityStep{
    ImageTracker tracker
    Binary binaryFilter = new Binary()

    GapConnectivityStep (ImageTracker tracker){
        this.tracker = tracker
    }
    Map<String, ImagePlus> run(ImagePlus gapRegionMask, ImagePlus nucleiMask, double ncuDia, double weight, String output){
        return tracker.runAndReturn({
            ImagePlus erodeMask = buildErodeConnectivity(gapRegionMask, nucleiMask, ncuDia)
            ImagePlus skeletonMask = buildSkeletonConnectivity(gapRegionMask, nucleiMask, ncuDia)
            ImagePlus refinedMask = refineMaskswithWegiht(erodeMask, skeletonMask, weight, output)
            return [erode: erodeMask, skeleton: skeletonMask, refined: refinedMask]
        }
        ) as Map<String, ImagePlus>
    }

    /*
    * 使用 ImageCalculator("Add create") 而非 copyBits。
    * 對於需經 setup(imp) / run(ip) 的 API（如 BinaryFilter），
    * in-place 操作可能導致 ImagePlus 與 ImageProcessor 狀態不一致，造成錯誤引用或結果不穩定。
    * 建立新的 ImagePlus 可確保行為正確且可重現。
     */
    private ImagePlus buildErodeConnectivity(ImagePlus gapRegionMask, ImagePlus nucleiMask, double ncuDia ){
        Prefs.padEdges = true
        ImagePlus erodeGap = gapRegionMask.duplicate()
        BinaryProcessor binaryP = new BinaryProcessor(erodeGap.getProcessor() as ByteProcessor)
        binaryP.erode(1, 0 )
        ImagePlus erodeMask = new ImageCalculator().run(erodeGap, nucleiMask,"Add create")
        binaryFilter.setup("close", erodeMask)
        binaryFilter.run(erodeMask.getProcessor())
        fillHoleWithSize(erodeMask, ncuDia)
        erodeMask.setTitle("erodeMask")
        return erodeMask
    }

    private ImagePlus buildSkeletonConnectivity(ImagePlus gapRegionMask, ImagePlus nucleiMask, double ncuDia ){
        IJ.run(gapRegionMask, "Skeletonize (2D/3D)", "")
        binaryFilter.setup("dilate", gapRegionMask)
        binaryFilter.run(gapRegionMask.getProcessor())

        ImagePlus skeletonMask = new ImageCalculator().run(gapRegionMask, nucleiMask, "Add create")
        binaryFilter.setup("close", skeletonMask)
        binaryFilter.run(skeletonMask.getProcessor())
        fillHoleWithSize(skeletonMask, ncuDia)
        skeletonMask.setTitle("skeletonMask")
        return skeletonMask
    }

    private ImagePlus refineMaskswithWegiht(ImagePlus erodeMask, ImagePlus skeletonMask, double weight, String output){
        EDM MaskEdm = new EDM()
        FloatProcessor erodeFp = MaskEdm.makeFloatEDM(erodeMask.getProcessor(), 0, true)
        FloatProcessor skeletonFp = MaskEdm.makeFloatEDM(skeletonMask.getProcessor(), 0, true)

        ///Combine Two Distance Map
        ImagePlus erodeEDM = new ImagePlus("erodeEDM", erodeFp)
        multiplyImp(erodeEDM, weight)
        ImagePlus skeletonEDM =  new ImagePlus("skeletonEDM", skeletonFp)
        multiplyImp(skeletonEDM, 1-weight)
        ImagePlus combineEDM = new ImageCalculator().run(erodeEDM, skeletonEDM, "Add create")

        ///Convert refined EDM to refined Mask
        double width = combineEDM.getStatistics().max
        combineEDM.getProcessor().setThreshold(1, width)
        def maskPr = combineEDM.createThresholdMask()
        combineEDM.setProcessor(output, maskPr)
        return combineEDM
    }

    private static fillHoleWithSize(ImagePlus Imp, double ncuDia){
        Imp.getProcessor().invert()
        def rm = RoiManager.getInstance2() ?: new RoiManager()
        rm.reset()
        ///Set selection parameter for ParticleAnalyzer
        int opts = ParticleAnalyzer.ADD_TO_MANAGER
        int meas = 0
        double maxSize = 1.5 * ncuDia * ncuDia
        def SizeFilter = new ParticleAnalyzer(opts, meas, new ResultsTable(),1, maxSize, 0.0, 1.0)
        SizeFilter.analyze(Imp)
        rm.runCommand(Imp, "Fill")
        Imp.getProcessor().invert()
        rm.reset()
    }

    private static multiplyImp(ImagePlus Imp, double weight) {
        def processor = Imp.getProcessor()
        def math = new ImageMath()
        math.applyMacro(processor, "v = v * ${weight}", false)
    }
}


// -----------------------------------------------------------------------------
// [2-2] Part3 – Extract MainChain
// -----------------------------------------------------------------------------

class SkeletonChainOps {
    //temporary data container of SkeletonChainOps
    static class MainChainPrepResult  {
        ImageProcessor objectMask   // 儲存物體的 Mask 影像
        List<Point> mainChain  // 儲存提取出的主骨架座標點清單
        Boolean isValid = null
    }

    /**
     * 處理並提取物件清單中的主鏈（Main Chains），包含：物件遮罩生成、初次骨架化、主鏈驗證及二次優化
     * 流程：
     * ROI → mask / skeleton → main chain 擷取 → 拓樸驗證
     * 若骨幹不穩定，則進行二次 skeletonize 後重新擷取
     * 設計目的：
     * - 避免 ROI 直接 skeletonize 產生不穩定分支
     * - 確保 main chain 具有穩定拓樸結構（避免 T-junction / noise）
     */
    static List<MainChainPrepResult> prepareMainChains(List<ObjectItem> objItemList, ImagePlus rawImg){
        // --- Step 1: 生成骨架與遮罩 ---
        AnalyzeSkeleton_ skel = new AnalyzeSkeleton_()
        objItemList.withIndex().collect{objItem, index ->
            // 強制宣告暫存標籤，確保 finally 抓得到
            Roi currentRoi = objItem.objectRoi
            def result = RetinaSpatialOps.roiToSkeletonImp(rawImg, currentRoi)
            ImagePlus skeletonImp = result.skeletonImp

            if(result == null || result.skeletonImp == null || result.objMask ==null){
                IJ.log ("[ERROR] - Object_${index}: Mask/Skeleton creation failed at root!")
                return new MainChainPrepResult(objectMask: null, mainChain: null, isValid: false)
            }
            // --- Step 2: 提取初步主鏈 ---
            ArrayList<Point> mainChain = extractMainChain(skeletonImp, false, skel)
            if (!mainChain) {
                IJ.log ("[ERROR] - fail to extract MainChain of Object_${index}!")

                return new MainChainPrepResult(objectMask: result.objMask.getProcessor(), mainChain: null, isValid: false)
            }
            // --- Step 3: 驗證與二次優化 ---
            // 若初步提取的主鏈不符合驗證標準（T-junction），則嘗試重新骨架化以優化結果
            def chainValidation = validateMainChain(mainChain)
            if(chainValidation == false){
                ImagePlus mainChainImp = drawMainChain(skeletonImp, mainChain)
                //tracker.addImp(mainChainImp)
                IJ.run(mainChainImp, "Skeletonize (2D/3D)", "")
                ArrayList<Point> mainChain2nd = extractMainChain(mainChainImp, false, skel)
                def chainValidation2nd = validateMainChain(mainChain2nd)
                if (chainValidation2nd == false){
                    return new MainChainPrepResult(objectMask: result.objMask.getProcessor(), mainChain: null, isValid: chainValidation2nd )
                }
                return new MainChainPrepResult(objectMask: result.objMask.getProcessor(), mainChain: mainChain2nd, isValid: chainValidation2nd)
            }
            return new MainChainPrepResult(objectMask: result.objMask.getProcessor(), mainChain: mainChain, isValid: chainValidation)
        }
    }


    static def extractMainChain(ImagePlus skeletonImp, boolean showResultImp, skel ){
        skel.calculateShortestPath = true
        skel.setup("", skeletonImp)
        def result = skel.run(AnalyzeSkeleton_.NONE, false, true, null, true, false )
        List<List<Point>> mainChainList = skel.getShortestPathPoints()
        if (showResultImp){
            def stack = skel.getResultImage(true)
            new ImagePlus("TRUNK_resultImage", stack).show()
        }
        if(!mainChainList || mainChainList.size() == 0 || mainChainList[0].size() ==0){
            IJ.log("[ERROR] - No main chain found. Skipping...")
            return null
        }
        if(mainChainList.size() > 1){
            IJ.log("Expected exactly one skeleton path for the ROI, but found ${mainChainList.size()}")
            return null
        }
        ArrayList<Point> mainChain = mainChainList[0].collect{p -> new Point((int) p.x, (int) p.y)}
        return mainChain
    }

    static drawMainChain(ImagePlus skeletonImp, ArrayList<Point> mainChain){
        int width = skeletonImp.getWidth()
        int height = skeletonImp.getHeight()
        ByteProcessor mainChainIp = new ByteProcessor(width, height)
        mainChain.each{Point p -> mainChainIp.set((int)p.x, (int)p.y, 255)}
        ImagePlus mainChainImp = new ImagePlus("mainChain", mainChainIp)
        return mainChainImp

    }
    private static divideImp(ImagePlus Imp, double weight) {
        def processor = Imp.getProcessor()
        def math = new ImageMath()
        math.applyMacro(processor, "v = v / ${weight}", false)

    }

    static validateMainChain(ArrayList<Point> mainChain){
        /**
         * Validate skeleton chain topology.
         * A valid chain must contain:
         *  - exactly two endpoints (1-neighbor)
         *  - all other points must have 2 neighbors
         */
        def chainSet = new HashSet<Point>(mainChain)
        int[] dx = [-1, 0, 1, -1, 1, -1, 0, 1]
        int[] dy = [-1, -1, -1, 0, 0, 1, 1, 1]
        //List<Integer> neighborCounts= []
        Point currentP = new Point(0,0)
        int n1 = 0
        for (Point p : mainChain){
            int count = 0

            for (int i=0; i < 8; i++){
                currentP.setLocation((int)p.x +dx[i], (int)p.y+dy[i])
                if(chainSet.contains( currentP)){
                    count++
                }
            }
            if(count ==1) n1++
            if(count == 0 || count >2 || n1 > 2){
                return false
            }
            //neighborCounts.add(count)
        }
        if( n1 != 2 ){
            return false
        }
        return true

    }
}

class RetinaSpatialOps{
    static Map<String, ImagePlus> roiToSkeletonImp(ImagePlus sourceImp, Roi roi){
        int width = sourceImp.getWidth()
        int height = sourceImp.getHeight()
        ByteProcessor bp = new ByteProcessor(width, height)
        bp.setValue(255)
        bp.fill(roi)
        ImagePlus objMaskImp = new ImagePlus("ObjectMask", bp)
        ImagePlus roiSkeletonImp = objMaskImp.duplicate()
        IJ.run(roiSkeletonImp, "Skeletonize (2D/3D)", "")
        return [objMask: objMaskImp,  skeletonImp: roiSkeletonImp]
    }

    static List<FloatProcessor> roiToEDM( List<ObjectItem> objItemList) {
        EDM currentEDM = new EDM()
        objItemList.withIndex().collect{obj, int index ->
            if(obj.objectMask == null) {
                IJ.log ("[Warning] Object_${index} has null mask. EDM calculation skipped.")
                return null
            }
            ImageProcessor ip = obj.objectMask
            FloatProcessor fp = currentEDM.makeFloatEDM(ip, 0, false)
            return fp
        }
    }

    static Roi chainToRoi(ArrayList<Point> chain, String roiName, Color color = new Color(0, 220, 255) ){
        if(!chain) return null
        int L = chain.size()
        float[] x = new float[L]
        float[] y = new float[L]
        for (int i = 0; i < L; i++){
            x[i] = chain[i].x as float
            y[i] = chain[i].y as float
        }
        Roi TempRoi = new PolygonRoi(x, y, L, Roi.POLYLINE)
        TempRoi.setStrokeColor(color)
        //TempRoi.setColor(new Color(0, 220, 255))
        TempRoi.setStrokeWidth(2 as float)
        TempRoi.setHandleSize(0)
        TempRoi.setName(roiName)
        return TempRoi
    }

    static ImagePlus combineAllEDM( List<ObjectItem> objItemList){
        int w = objItemList[0].objEDM.getWidth()
        int h = objItemList[0].objEDM.getHeight()
        FloatProcessor totalObjEDM = new FloatProcessor(w,h)
        if(objItemList.size() == 0) return null
        objItemList.each{obj ->
            totalObjEDM.copyBits(obj.objEDM, 0,0, Blitter.ADD)
        }
        ImagePlus impCombinedEDM = new ImagePlus("Combined_Object_EDM",totalObjEDM )
        return impCombinedEDM
    }

    static ImagePlus doCCL(List <ImageProcessor> Ips){
        if(Ips.size() == 0) return null
        int w = Ips[0].getWidth()
        int h = Ips[0].getHeight()
        ImageProcessor CCLIp = new FloatProcessor(w,h)
        Ips.eachWithIndex{Ip, Index ->
            CCLIp.setValue(Index+1)
            CCLIp.fill(Ip)
        }
        ImagePlus CCL = new ImagePlus("CCL", CCLIp)
        return CCL
    }
}

class MeasurementOps{
    static float[] measureEDMProfile (FloatProcessor fp, List<Point> mainChain){
        if ( !fp || !mainChain) {return null}
        fp.setInterpolationMethod(ImageProcessor.BILINEAR)
        List<Double> result = mainChain.collect{Point P ->
            fp.getInterpolatedPixel((double)P.x, (double) P.y ) * 2 // 將 EDM 測得的半徑數值轉換為直徑 (Diameter = Radius * 2)
        }
        return result as float[]
    }
}


// -----------------------------------------------------------------------------
// [2-3] Part3 – Refine and  Validate mainChain
// -----------------------------------------------------------------------------

class RefineMainChainOps{
    static List<List<Point>> refineMainChains(List<ObjectItem> objItemList, int ncuDia ) {
        /**
         * 優化並修剪物件清單中的主鏈（Main Chains），包含：法線路徑驗證與端點不穩定段落剔除
         * 流程：
         * 從主鏈兩端向中心掃描 -> 於各點建立法線 -> 驗證法線是否先碰觸到物件邊緣
         * 設計目的：
         * - 確保主鏈端點具有穩定的拓樸結構，影像邊緣雜訊干擾
         * - 利用 ncuDia (核仁直徑) 作為物理步長，確保量測區域的代表性
         * - 採用 ImageProcessor 進行過渡運算，優化像素存取效能並減少記憶體開銷
         **/
        // --- Step 1: 預備空間常量 ---
        int width = objItemList[0].objectMask.getWidth()
        int height = objItemList[0].objectMask.getHeight()
        double maxLen = Math.sqrt(width * width + height * height)
        maxLen = Math.round(maxLen)

        objItemList.withIndex().collect{ObjectItem objItem, int Index ->
            IJ.log("【MainChainRefine】 Processing object ${Index}/${objItemList.size()}")

            if(!objItem.mainChain) return null

            // --- Step 2: 準備掃描區間 ---
            // 將主鏈座標拆分為左右兩段，由端點向中間匯合掃描
            ArrayList<Point> mainChain = objItem.mainChain
            int chainSize = mainChain.size()
            List<Point> leftMainChain = mainChain[0..Math.round(chainSize/2)]
            List<Point> rightMainChain = mainChain[-1..Math.round(chainSize/2)]

            def validChainRegion = [null, null] //暫存有效索引：[左端起點, 右端起點]
            ImageProcessor currentObjMask = objItem.objectMask

            //--- Step 3: 端點法線驗證掃描 ---
            int count = -1
            for(chain in [leftMainChain, rightMainChain]) {
                count +=1
                if(count == 0){
                    IJ.log("【MainChainRefine】 Validating left endpoint (${Index}/${objItemList.size()})...")
                }
                else{
                    IJ.log("【MainChainRefine】 Validating right endpoint (${Index}/${objItemList.size()})...")
                }
                // 由骨架中心向末梢遍歷，步長基準為 ncuDia
                for (int seg = chain.size()-1 ; seg - ncuDia >=0 ; seg -= ncuDia) {
                    Point startP = chain[seg]
                    Point endP = chain[seg - ncuDia]
                    Point centerP = chain[seg - ncuDia.intdiv(2)]
                    def NormalLine = makeNormalLine(startP, endP, centerP, (int) maxLen) // 建立局部法線路徑 (Normal Line)

                    if (!validateNormalPath(NormalLine, currentObjMask)) {
                        validChainRegion[count] = seg + 1
                        break}
                }
            }
            // --- Step 4: 依據驗證結果修剪主鏈 ---
            if (validChainRegion.contains(null)){
                IJ.log ("【Error】- Main chain validation failed at object_${Index} !")
                return null
            }
            int cutLeft = validChainRegion[0] as int
            int cutRight =  validChainRegion[1] as int
            IJ.log ("【MainChainRefine】- Scan complete. Valid segment index: ${cutLeft}–${mainChain.size() - cutRight} !")
            return mainChain[cutLeft .. -cutRight]
        }
    }
    /**
     * 建立法線路徑 (Normal Line)
     * @param startP    骨架段落起點
     * @param endP      骨架段落終點
     * @param centerP   法線中心點
     * @param extension 該法線由中心向外延伸的長度
     */
    static ArrayList<Point> makeNormalLine(Point startP, Point endP, Point centerP, int extension){
        double dx = endP.x - startP.x
        double dy = endP.y - startP.y
        double length = Math.sqrt(dx*dx + dy*dy)
        double tx = dx / length * extension
        double ty = dy / length * extension
        Point startNP = new Point( (int) Math.round(centerP.x -ty), (int)  Math.round(centerP.y + tx))
        Point  endNP = new Point( (int) Math.round(centerP.x +ty), (int) Math.round(centerP.y -tx))
        ArrayList <Point> result = [startNP, centerP, endNP]
        return result
    }
    /**
     * 驗證法線路徑是否完全處於物件遮罩（Object Mask）內部
     * 邏輯：
     * 從中心點 (centerP) 向延伸法線的兩端 (startNP, endNP) 進行像素探測。
     * 只要法線穿出物件先碰到（碰到背景 0）-> 骨架穩定；先碰到影像邊界 (NaN) -> 骨架點不穩定。
     *
     * @param normalLine 包含 [startNP, centerP, endNP] 的法線路徑清單
     * @param objMask    物件遮罩的 ImageProcessor (8-bit)
     * @return boolean   法線先碰物件邊界 true
     */
    static boolean validateNormalPath(ArrayList<Point> normalLine, ImageProcessor objMask) {
        def startNP = normalLine[0]
        def centerP = normalLine[1]
        def endNP = normalLine[2]
        // --- Step 1: 取得像素剖面 (Profile) --- (startNP 和 endNP)
        def LefNormalL = objMask.getLine((double) centerP.x, (double) centerP.y,
                (double) startNP.x, (double) startNP.y)

        def RightNormalL = objMask.getLine((double) centerP.x, (double) centerP.y,
                (double) endNP.x, (double) endNP.y)

        // --- Step 2: 完整性檢查 ---
        for (Profile in [LefNormalL, RightNormalL]) {
            for (int p = 0; p < Profile.size(); p++) {
                if (Profile[p] == 255) continue // 在物件內快速跳過
                if (Double.isNaN(Profile[p])) {
                    return false // 法線穿出物件碰到影像邊界
                }
                if (Profile[p] < 255) {
                    break // 法線穿出物件碰到背景
                }
            }
        }
        return true
    }
}

// =============================================================================
// [3] APP – Pipeline Orchestration (Flow Control)
// =============================================================================
// 這裡負責把 ENGINE 串起來

//------------------------------------------------------------------------------
// 【3-0】Loading Logic
//------------------------------------------------------------------------------
ImagePlus loadValidImage() {
    while(true) {
        def input = askUserInput()
        if(input == null){
            IJ.showMessage("The process was terminated early by the user.")
            return null
        }
        String pathName = input.ImgPath
        Path path = Paths.get(pathName)
        if (!Files.exists(path)) {
            IJ.showMessage("Error" , "File does not exist.")
            continue
        }
        String checkPath = pathName.toLowerCase()
        if (!checkPath.endsWith("tiff") && !checkPath.endsWith("tif")){
            IJ.showMessage("Error" , "Only support tiff and tif flies")
            continue
        }

        if (input.x != input.y){
            IJ.showMessage("Error" , "Image is anisotropic.Pleas check the Scaling!! ")
            continue
        }

        ImagePlus ori = IJ.openImage(pathName)
        def imgType = ori.getType()
        if(imgType != ImagePlus.COLOR_RGB){
            println "\"Invalid image type. Only color (RGB) TIFF images are supported.\""
            continue
        }
        ori.setProperty("SCALING", input.x.value)
        ori.setProperty("SCALING_Unit", input.y.unit)
        ori.setProperty("lifeCycle", "Keep")
        ori.setProperty("FilePath", pathName)
        return ori
    }
}

//------------------------------------------------------------------------------
// 【3-1】extract nucleus region
//------------------------------------------------------------------------------
double askNucleusDiameter(ImagePlus ori){
    IJ.setTool(4)
    nonBlockDialog("Measure Nuclear Diameter", "Use the Line Tool to draw the widest diameter of a nucleus.", ori, this.&checkLine)
    Roi roi = ori.getRoi()
    if(roi instanceof Line){
        double nucleus_size = (roi as Line).getRawLength()
        return nucleus_size
    }
}

def runNucleusExtraction(PipelineContext ctx) {
    ImageTracker tracker = ctx.getImgTracker()
    Boolean batchMode = ctx.getBatchMode()
    NucleiChannelExtractor nucExtractor = new NucleiChannelExtractor(tracker, batchMode)
    ExtractGapRegionStep gapExtractor = new ExtractGapRegionStep(tracker)
    GapConnectivityStep ConnMasker = new GapConnectivityStep(tracker)
    double nucDia = ctx.getNcuDia()
    ImagePlus ori = ctx.getRawColorImg()

    ImagePlus oriMask = nucExtractor.run(ori, nucDia )
    ImagePlus gapRegion = gapExtractor.run(oriMask, nucDia)
    Map<String, ImagePlus> connMasks = ConnMasker.run(gapRegion, oriMask, nucDia, 0.7, "refinedMask")
    ImagePlus refinedMask = connMasks.refined
    refinedMask.setProperty("lifeCycle", "Keep")
    if(batchMode) Interpreter.batchMode = false
    refinedMask.show()
    tracker.getTrackedImages().each { imp ->
        println "Tracked: ${imp.getTitle()}  (ID=${imp.getID()})"
    }
    tracker.cleanupByLifeCycle(true)
    ctx.setRefinedMask(refinedMask)

}

//------------------------------------------------------------------------------
// 【3-2】Manually refine Mask and select object region
//------------------------------------------------------------------------------
static void launchAndWaitMaskGui(PipelineContext ctx){
    def latch = new CountDownLatch(1)
    def controller = new MaskGuiController(ctx, latch)
    SwingUtilities.invokeLater { controller.show() }
    latch.await()
}

//------------------------------------------------------------------------------
// 【4-1】Extract mainchain from object and validation
//------------------------------------------------------------------------------

static prepareAndAnalysisFeatures(PipelineContext ctx){
    ImagePlus rawImg = ctx.getRawColorImg()
    int ncuDia = Math.round(ctx.getNcuDia())
    double scale = ctx.getRawColorImg().getProperty("SCALING") as double
    String unit = ctx.getRawColorImg().getProperty("SCALING_Unit")


    List objItemList = ctx.getObjectStore().getAll()

    List<SkeletonChainOps.MainChainPrepResult> chainMetadata = SkeletonChainOps.prepareMainChains(objItemList, rawImg)

    objItemList.eachWithIndex{ ObjectItem obj, int i ->
        obj.objectMask = chainMetadata[i].objectMask
        obj.mainChain = chainMetadata[i].mainChain
    }
    List<List<Point>> refineMainChains = RefineMainChainOps.refineMainChains(objItemList, ncuDia)
    List<FloatProcessor> EDMs = RetinaSpatialOps.roiToEDM(objItemList)

    objItemList.eachWithIndex{ ObjectItem  obj, int it ->
        obj.thicknessProfile = MeasurementOps.measureEDMProfile(EDMs[it], refineMainChains[it])
        obj.tkAnalysisResult = obj.getThicknessAnalysis(ncuDia, scale, unit)
        obj.refinedMainChain = refineMainChains[it]
    }
}

static void visualiseData(PipelineContext ctx){
    // --- Step 1: 轉換 mainChain 成 RoiItem ---
    ImageTracker tracker = ctx.getImgTracker()
    List<RoiItem> RoiItems = ctx.getRoiStore().getFinalObjs()
    List objItemList = ctx.getObjectStore().getAll()
    objItemList.eachWithIndex{ ObjectItem obj, int i ->
        RoiItems[i].skeletonRoi_Rf = RetinaSpatialOps.chainToRoi(obj.refinedMainChain, "reSkeleton_${i}")
        RoiItems[i].skeletonRoi = RetinaSpatialOps.chainToRoi(obj.mainChain, "Skeleton_${i}", new Color(0,49,170))
    }
    // --- Step 2: 將所有物件相關 Roi 加入 Roi Manage ---
    RoiManager rm = RoiManager.getInstance() ?: new RoiManager()
    rm.reset()
    RoiItems.eachWithIndex{RoiItem roiItem, int i->
        roiItem.roi.setName("Object_${i}")
        rm.addRoi(roiItem.roi)
        rm.addRoi(roiItem.skeletonRoi_Rf)
        rm.addRoi(roiItem.skeletonRoi)
    }
    // --- Step 3: 物件轉成 CCL 標記 ---
    List<ImageProcessor> objectMasks = objItemList.collect{obj ->
        if (!obj.objectMask){
            return null
        }
        return obj.objectMask
    }
    ImagePlus CCL = tracker.runAndReturn({RetinaSpatialOps.doCCL(objectMasks)})
    CCL.setProperty("lifeCycle", "Keep")

    // --- Step 4: 量測結果填入表格
    ResultsTable finalTable = new ResultsTable()
    for (int i = 0; i < objItemList.size(); i++){
        Map<String, Double> results = objItemList[i].tkAnalysisResult
        finalTable.incrementCounter()
        results.each{entry -> finalTable.addValue( entry.key as String, entry.value as double)}
    }
    finalTable.show("Thickness Analysis")

    // --- Step 5: 自動存檔 ---
    String oriPath = ctx.getRawColorImg().getProperty("FilePath")
    String ImgName = Paths.get(oriPath).getFileName().toString()
    ImgName = ImgName.substring( 0, ImgName.lastIndexOf('.'))
    Path outPutPath = Paths.get(oriPath).getParent().resolve(ImgName)
    Files.createDirectories(outPutPath)

    Path TablePath = outPutPath.resolve( ImgName + ".csv")
    finalTable.save(TablePath.toString())
    Path CCLPath = outPutPath.resolve( ImgName + "_CCL.tiff")
    IJ.saveAs(CCL , "Tiff", CCLPath.toString())
    Path rfMaskPath = outPutPath.resolve( ImgName + "_refinedMask.tiff")
    IJ.saveAs(ctx.getRefinedMask(), "Tiff", rfMaskPath.toString())

}



// =============================================================================
// [4] UI – User Interaction & Controllers
// =============================================================================
//------------------------------------------------------------------------------
// 【4-0】Loading Logic
//------------------------------------------------------------------------------
def askUserInput() {
    //// -- 1. Ask the file path and scale information --////
    def openfont = new Font("SansSerif", Font.BOLD, 15)
    def darkRed = new Color(227, 23, 13)
    def openImg = new GenericDialog("Select your image")
    openImg.addFileField("Select file(tiff):", null)
    openImg.addMessage("Change the scale if need:    ", openfont, darkRed)
    //set scale(Pixel width, Pixel height, number unit)
    openImg.addNumericField("Pixel width :", 1, 2)
    openImg.addToSameRow()
    openImg.addStringField("unit:", "pixel")
    openImg.addNumericField("Pixel height:", 1, 2)
    openImg.addToSameRow()
    openImg.addStringField("unit:", "pixel")
    openImg.showDialog()
    String pathName = openImg.getNextString()
    if (openImg.wasCanceled()) {
        return null//throw new IllegalStateException("The process was terminated early by the user.")
    }
    /// --Fetch the parameters from dialog-- ///
    return [
            ImgPath: pathName,
            x : [ value: openImg.getNextNumber(), unit: openImg.getNextString()],
            y : [ value: openImg.getNextNumber(), unit: openImg.getNextString()]
    ]
}

//------------------------------------------------------------------------------
// 【4-1】Asking the diameter for reference
//------------------------------------------------------------------------------
def nonBlockDialog(title, content, Imp, Closure check) {
    def locker = new Object()
    SwingUtilities.invokeLater {
        def frame = new JFrame(title)
        frame.setSize(520, 150)
        frame.setLayout(new BorderLayout(10, 10))
        def message = new JLabel("<html><div style='font-size:14pt'> ${content}<br></div></html>")
        message.setBorder(BorderFactory.createEmptyBorder(15, 20, 10, 20))
        def btnContinue = new JButton("Done")
        def btnCancel = new JButton("Cancel")
        /// -- Set the function of buttons
        btnContinue.addActionListener {
            synchronized (locker) {
                if (check(Imp) == true) {
                    locker.notify()
                    frame.dispose()
                }
            }
        }
        btnCancel.addActionListener {
            synchronized (locker) {
                println "The process was terminated early by the user."
                locker.notify()
            }
            frame.dispose()
            return
        }
        def btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10))
        btnPanel.add(btnContinue)
        btnPanel.add(btnCancel)
        frame.add(message, BorderLayout.CENTER)
        frame.add(btnPanel, BorderLayout.SOUTH)
        frame.setResizable(false)
        frame.setAlwaysOnTop(false)
        frame.setVisible(true)

    }
    synchronized (locker){
        locker.wait()
    }
}

def checkLine(Imp) {
    def roi = Imp.getRoi()
    if (roi == null || roi.getType() != Roi.LINE){
        println "Please draw a line with line tool for measuring the Diameter of nucleus"
        return false
    }
    return true
}

//------------------------------------------------------------------------------
// 【4-2】Manually refine Mask and select object region
//------------------------------------------------------------------------------

class MaskGuiController {
    PipelineContext ctx
    CountDownLatch latch
    OverlayManager oManager
    EmptyTool empty
    MaskBrushTool maskBrush
    def BG_MAIN = Color.decode("#e6effc")
    MaskWandTool maskWand
    //RoiStore roiStore

    MaskGuiController(PipelineContext ctx, CountDownLatch latch) {
        this.ctx = ctx
        this.latch = latch
    }

    def show() {
        println "[show] isEDT=" + SwingUtilities.isEventDispatchThread() + " thread=" + Thread.currentThread().name
        initialization()
        def frame = new JFrame("Mask refinement")
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
        frame.setAlwaysOnTop(true)
        def RootPanel = new JPanel(new BorderLayout())

        JPanel MPanel = buildMainPanel()
        JPanel BPanel = buildBottomPanel(frame)

        RootPanel.add(MPanel, BorderLayout.CENTER)
        RootPanel.add(BPanel, BorderLayout.SOUTH)

        frame.setContentPane(RootPanel)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.setVisible(true)
    }

    def initialization() {
        OverlayManager oManager = new OverlayManager(ctx)
        oManager.CreateBacKG()
        this.oManager = oManager

        EmptyTool empty = new EmptyTool()
        empty.registrator()
        this.empty = empty

        MaskBrushTool maskBrush = new MaskBrushTool()
        maskBrush.registrator()
        this.maskBrush = maskBrush

        MaskWandTool maskWand = new MaskWandTool({ ImagePlus imp, Roi roi ->
            oManager.CreateBacKG()
            oManager.setDisplay([roi])
            oManager.setText([null])
        })
        maskWand.registrator()
        this.maskWand = maskWand
    }

    JPanel buildMainPanel() {
        def MPanel = new JPanel(new GridBagLayout())
        //initialization()
        MPanel.setBackground(BG_MAIN)
        GridBagConstraints gbcMPanel = new GridBagConstraints()
        gbcMPanel.gridx = 0; gbcMPanel.gridy = 0; gbcMPanel.anchor = GridBagConstraints.NORTHWEST
        gbcMPanel.fill = GridBagConstraints.HORIZONTAL; gbcMPanel.weightx = 1
        gbcMPanel.insets = new Insets(2, 0, 2, 0)

        MPanel.add(buildDisplayAdjustPanel(), gbcMPanel); gbcMPanel.gridy++

        def Brush = buildBrushPanel(maskBrush)
        def Wand = buildWandPanel(maskWand)
        MPanel.add(Brush.Panel, gbcMPanel); gbcMPanel.gridy++
        MPanel.add(Wand.Panel, gbcMPanel); gbcMPanel.gridy++
        BtnSwitcher(Brush.Radiobutton, Wand.Btn, Brush.WidgetGroup, Wand.WidgetGroup, maskBrush, maskWand, empty)
        return MPanel
    }

    JPanel buildDisplayAdjustPanel() {
        /// == Display region UI == ///
        JLabel OpacityLabel
        JSlider OpacitySlider
        JCheckBox ShowCheck
        def DisplayAdjustPanel = new JPanel(new GridBagLayout())
        DisplayAdjustPanel.setBackground(BG_MAIN)
        GridBagConstraints gbcDAPanel = new GridBagConstraints()
        gbcDAPanel.gridx = 0; gbcDAPanel.gridy = 0; gbcDAPanel.anchor = GridBagConstraints.WEST;
        gbcDAPanel.fill = GridBagConstraints.HORIZONTAL; gbcDAPanel.weightx = 1;
        gbcDAPanel.insets = new Insets(6, 8, 6, 8)

        def ZoneTitle = new JLabel("Display adjustment")
        ZoneTitle.setFont(new Font("Microsoft JhengHei", Font.BOLD, 14))
        ZoneTitle.setForeground(Color.decode("#1e6350"))

        OpacityLabel = new JLabel(String.format("Raw image opacity on mask : %d%%", (int) (oManager.getOpacity() * 100)))
        OpacitySlider = new JSlider(0, 100, (int) (oManager.getOpacity() * 100))
        OpacitySlider.setMajorTickSpacing(25); OpacitySlider.setMinorTickSpacing(5); OpacitySlider.setPaintTicks(true)

        ShowCheck = new JCheckBox("Show Raw image", true)

        def Seperator = new JSeparator()

        DisplayAdjustPanel.add(ZoneTitle, gbcDAPanel); gbcDAPanel.gridy++
        DisplayAdjustPanel.add(OpacityLabel, gbcDAPanel); gbcDAPanel.gridy++
        DisplayAdjustPanel.add(OpacitySlider, gbcDAPanel); gbcDAPanel.gridy++
        DisplayAdjustPanel.add(ShowCheck, gbcDAPanel); gbcDAPanel.gridy++
        DisplayAdjustPanel.add(Seperator, gbcDAPanel)

        /// == Display Widgets Listeners == ///
        OpacitySlider.addChangeListener({ event ->
            double newOpacity = OpacitySlider.getValue() / 100
            OpacityLabel.setText(String.format("Transparency of Raw image: %d%%", (int) (newOpacity * 100)))
            oManager.setOpacity(newOpacity)
            oManager.CreateBacKG()
        } as ChangeListener)

        ShowCheck.addActionListener({ event ->
            boolean ShowState = ShowCheck.isSelected()
            if (!ShowState) {
                oManager.setShowImg(false)
                //oManager.setOpacity(0)
                //oManager.CreateBacKG()
            } else {
                oManager.setShowImg(true)
                //double newOpacity = OpacitySlider.getValue() / 100
                //oManager.setOpacity(newOpacity)
                //oManager.CreateBacKG()
            }
        } as ActionListener)

        return DisplayAdjustPanel
    }

    def buildBrushPanel(MaskBrushTool MaskBrush) {
        /// == Brush panel UI == ///
        int initBrush = 25
        Boolean blackBrush = true
        JPanel BrushPanel = new JPanel(new GridBagLayout())
        BrushPanel.setBackground(BG_MAIN)
        GridBagConstraints gbcBPanel = new GridBagConstraints()
        gbcBPanel.gridx = 0; gbcBPanel.gridy = 0; gbcBPanel.anchor = GridBagConstraints.WEST;
        gbcBPanel.fill = GridBagConstraints.HORIZONTAL; gbcBPanel.weightx = 1
        gbcBPanel.insets = new Insets(6, 4, 6, 8)

        JLabel ZoneTitle = new JLabel("【Brush tool】")
        ZoneTitle.setFont(new Font("Microsoft JhengHei", Font.BOLD, 14))
        ZoneTitle.setForeground(Color.decode("#2e587f"))

        JLabel BrushHint = new JLabel("Use the brush to refine the mask before selecting objects.")
        JRadioButton BrushEnableBtn = new JRadioButton("Enable Brush", false)
        BrushEnableBtn.setFont(new Font("Microsoft JhengHei", Font.BOLD, 12))

        JPanel SliderPanel = new JPanel(new GridBagLayout())
        SliderPanel.setBackground(BG_MAIN)
        GridBagConstraints gbcSpanel = new GridBagConstraints()
        gbcSpanel.gridx = 0; gbcSpanel.gridy = 0; gbcSpanel.anchor = GridBagConstraints.WEST;
        gbcSpanel.fill = GridBagConstraints.NONE; gbcSpanel.weightx = 0
        gbcSpanel.insets = new Insets(0, 2, 6, 0)

        JLabel BrushLabel = new JLabel("Brush size")
        JSlider BrushSlider = new JSlider(1, 50, initBrush)

        JLabel BrushSize = new JLabel(String.format("(%d px)", initBrush))

        JPanel BrushBtnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8))
        BrushBtnPanel.setBackground(BG_MAIN)
        def fillMask = new JButton("Fill  Mask (Black)")
        def splitMask = new JButton("Split Mask(White)")
        def defaultBorder = splitMask.getBorder()
        def activeBorder = BorderFactory.createCompoundBorder(new LineBorder(Color.blue, 1, true), defaultBorder)
        fillMask.setBorder(activeBorder)
        fillMask.setBackground(Color.decode("#fafbfc"))
        splitMask.setBackground(Color.decode("#fafbfc"))


        JLabel BrushColorLabel = new JLabel(String.format("Brush color: %s", blackBrush ? "Black " : "White"))
        def Seperator = new JSeparator()

        BrushPanel.add(ZoneTitle, gbcBPanel); gbcBPanel.gridy++; gbcBPanel.insets = new Insets(0, 10, 6, 8)
        BrushPanel.add(BrushHint, gbcBPanel); gbcBPanel.gridy++; gbcBPanel.insets = new Insets(6, 6, 6, 8)
        BrushPanel.add(BrushEnableBtn, gbcBPanel); gbcBPanel.gridy++; gbcBPanel.insets = new Insets(0, 25, 3, 8)

        BrushPanel.add(SliderPanel, gbcBPanel); gbcBPanel.gridy++; gbcBPanel.insets = new Insets(6, 6, 0, 8)
        SliderPanel.add(BrushLabel, gbcSpanel); gbcSpanel.gridx++; gbcSpanel.fill = GridBagConstraints.HORIZONTAL;
        gbcSpanel.weightx = 1; gbcSpanel.insets = new Insets(0, 0, 6, 2)
        SliderPanel.add(BrushSlider, gbcSpanel); gbcSpanel.gridx++; gbcSpanel.fill = GridBagConstraints.NONE;
        gbcSpanel.weightx = 0; gbcSpanel.insets = new Insets(0, 2, 6, 2)
        SliderPanel.add(BrushSize, gbcSpanel);

        BrushPanel.add(BrushBtnPanel, gbcBPanel); gbcBPanel.gridy++; gbcBPanel.insets = new Insets(0, 10, 0, 8)
        BrushBtnPanel.add(fillMask)
        BrushBtnPanel.add(splitMask)

        BrushPanel.add(BrushColorLabel, gbcBPanel); gbcBPanel.gridy++; gbcBPanel.insets = new Insets(14, 8, 6, 8)
        BrushPanel.add(Seperator, gbcBPanel)

        //// ==Brush Widgets Listeners ==////
        fillMask.addActionListener({ event ->
            blackBrush = true
            MaskBrush.setColor(blackBrush)
            //blackBrush ? Toolbar.setForegroundColor(Color.black):Toolbar.setForegroundColor(Color.WHITE)
            fillMask.setBorder(activeBorder)
            splitMask.setBorder(defaultBorder)
            BrushColorLabel.setText(String.format("Brush color: %s", blackBrush ? "Black " : "White"))
        } as ActionListener)

        splitMask.addActionListener({ event ->
            blackBrush = false
            MaskBrush.setColor(blackBrush)
            //blackBrush ? Toolbar.setForegroundColor(Color.black):Toolbar.setForegroundColor(Color.WHITE)
            fillMask.setBorder(defaultBorder)
            splitMask.setBorder(activeBorder)
            BrushColorLabel.setText(String.format("Brush color: %s", blackBrush ? "Black " : "White"))
        } as ActionListener)

        BrushSlider.addChangeListener({ event ->
            int Sizelabel = BrushSlider.getValue()
            MaskBrush.setDiameter(Sizelabel)
            BrushSize.setText(String.format("(%d px)", Sizelabel))
        } as ChangeListener)

        BrushEnableBtn.addActionListener({ event ->
            if (BrushEnableBtn.isSelected()) {
                println("newBrush:" + BrushSlider.getValue())
                //MaskBrush.select()
            }
        } as ActionListener)
        return [Panel: BrushPanel, Radiobutton: BrushEnableBtn, WidgetGroup: [BrushSlider, fillMask, splitMask]]
    }

    def buildWandPanel(MaskWandTool tool) {
        /// == Wand panel UI == ///
        JPanel WandPanel = new JPanel(new GridBagLayout())
        WandPanel.setBackground(BG_MAIN)
        GridBagConstraints gbcWPanel = new GridBagConstraints()
        gbcWPanel.gridx = 0; gbcWPanel.gridy = 0; gbcWPanel.anchor = GridBagConstraints.WEST;
        gbcWPanel.fill = GridBagConstraints.HORIZONTAL; gbcWPanel.weightx = 1
        gbcWPanel.insets = new Insets(6, 4, 6, 8)

        JLabel ZoneTitle = new JLabel("【Wand tool】")
        ZoneTitle.setFont(new Font("Microsoft JhengHei", Font.BOLD, 14))
        ZoneTitle.setForeground(Color.decode("#582e7f"))
        JLabel WandHint = new JLabel("Use Wand to pick objects. Manage them using Add/Delete below.")
        JRadioButton WandEnableBtn = new JRadioButton("Enable Wand", false)
        WandEnableBtn.setFont(new Font("Microsoft JhengHei", Font.BOLD, 12))

        JPanel WandRoiPanel = new JPanel(new GridBagLayout())
        //WandRoiPanel.setBackground(BG_MAIN)
        WandRoiPanel.setBackground(Color.decode("#F6F2FF"))
        WandRoiPanel.setBorder(new LineBorder(Color.decode("#D8D8E0"), 1, true))
        GridBagConstraints gbcWRPanel = new GridBagConstraints()
        gbcWRPanel.gridx = 0; gbcWRPanel.gridy = 0; gbcWRPanel.anchor = GridBagConstraints.WEST;
        gbcWRPanel.fill = GridBagConstraints.HORIZONTAL; gbcWRPanel.weightx = 1
        gbcWRPanel.insets = new Insets(6, 4, 0, 8)

        JLabel WandRoiPanelTitle = new JLabel("Object/ROI List")
        //DefaultListModel<RoiItem> ObjectLst = new DefaultListModel() // be used to storage the List for JList(Display)
        JList<RoiItem> ObjectDisplayFrame = new JList(ctx.getRoiStore().getObjectLst()) //  display List
        ObjectDisplayFrame.setSelectionMode(ListSelectionModel.SINGLE_SELECTION) // 只能單選
        def ScrollPlane = new JScrollPane(ObjectDisplayFrame)
        ScrollPlane.setPreferredSize(new Dimension(200, 100))

        JPanel WandBtnPanel = new JPanel(new GridBagLayout())
        WandBtnPanel.setBackground(Color.decode("#F6F2FF"))
        GridBagConstraints gbcWandBtn = new GridBagConstraints()
        gbcWandBtn.gridx = 0; gbcWandBtn.gridy = 0; gbcWandBtn.anchor = GridBagConstraints.WEST
        gbcWandBtn.fill = GridBagConstraints.HORIZONTAL; gbcWandBtn.weightx = 1
        gbcWandBtn.insets = new Insets(6, 0, 6, 0)

        JButton AddRoi = new JButton("Add ")
        JButton DeleteRoi = new JButton("Delete ")
        JCheckBox ShowAll = new JCheckBox("Show All ROI", false)

        WandPanel.add(ZoneTitle, gbcWPanel); gbcWPanel.gridy++; gbcWPanel.insets = new Insets(0, 10, 6, 8)
        WandPanel.add(WandHint, gbcWPanel); gbcWPanel.gridy++; gbcWPanel.insets = new Insets(6, 6, 0, 8)
        WandPanel.add(WandEnableBtn, gbcWPanel); gbcWPanel.gridy++; gbcWPanel.insets = new Insets(0, 22, 10, 8)
        WandPanel.add(WandRoiPanel, gbcWPanel); gbcWPanel.gridy++

        WandRoiPanel.add(WandRoiPanelTitle, gbcWRPanel); gbcWRPanel.gridy++; gbcWRPanel.insets = new Insets(0, 4, 0, 8)
        WandRoiPanel.add(ScrollPlane, gbcWRPanel); gbcWRPanel.gridx++; gbcWRPanel.fill = GridBagConstraints.VERTICAL;
        gbcWRPanel.weighty = 1; gbcWRPanel.insets = new Insets(0, 0, 0, 0)
        WandRoiPanel.add(WandBtnPanel, gbcWRPanel)

        WandBtnPanel.add(AddRoi, gbcWandBtn); gbcWandBtn.gridy++
        WandBtnPanel.add(DeleteRoi, gbcWandBtn); gbcWandBtn.gridy++
        WandBtnPanel.add(ShowAll, gbcWandBtn)

        //// ==WandPanel Widgets Listeners ==////
        int ObjIndex = 0
        AddRoi.addActionListener({ ActionEvent it ->
            if (!WandEnableBtn.isSelected()) return
            Roi currentRoi = tool.getSelectRoi()
            if (currentRoi == null) return
            ObjectDisplayFrame.clearSelection()
            RoiItem item = new RoiItem(roi: currentRoi, label: "ROI ${ObjIndex}", componentID: ObjIndex + 1)
            item.createLabelRoi()
            ctx.getRoiStore().addRoi(item)
            ObjectDisplayFrame.clearSelection()
            ObjIndex = ObjIndex + 1
            tool.setSelectRoi(null)
        } as ActionListener)

        DeleteRoi.addActionListener({ ActionEvent it ->
            if (!WandEnableBtn.isSelected()) return
            if (ObjectDisplayFrame.isSelectionEmpty()) return
            //RoiItem deleteRoi = ObjectDisplayFrame.getSelectedValue()
            int Indx = ObjectDisplayFrame.getSelectedIndex()
            ctx.getRoiStore().deleteRoi(Indx)
            oManager.setDisplay([null])
            oManager.setText([null])
        } as ActionListener)

        ObjectDisplayFrame.addListSelectionListener({ ListSelectionEvent it ->
            if (!WandEnableBtn.isSelected()) return
            if (ObjectDisplayFrame.isSelectionEmpty()) return
            ShowAll.setSelected(false)
            RoiItem selectItem = ObjectDisplayFrame.getSelectedValue()
            Roi selectRoi = selectItem.roi
            selectRoi.setColor(new Color(0, 220, 255))
            selectRoi.setStrokeWidth(3 as float)
            oManager.setDisplay([selectRoi])
            oManager.setText([selectItem.getTextRoi()])
        } as ListSelectionListener)

        ShowAll.addActionListener({ ActionEvent it ->
            if (!WandEnableBtn.isSelected()) return
            ObjectDisplayFrame.clearSelection()
            List<Roi> obj = []
            List<TextRoi> label = []
            if (ShowAll.isSelected()) {
                obj.addAll(ctx.getRoiStore().getRois())
                label.addAll(ctx.getRoiStore().getRoiLabel())
            } else {
                obj.add(null)
                label.add(null)
            }
            oManager.setDisplay(obj)
            oManager.setText(label)
        } as ActionListener)
        return [Panel: WandPanel, Btn: WandEnableBtn, WidgetGroup: [AddRoi, DeleteRoi, ShowAll]]
    }

    JPanel buildBottomPanel(JFrame MainFrame) {
        JPanel BottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6))
        JButton CancelBtn = new JButton("Cancel")
        JButton OkBtn = new JButton("Apply")

        BottomPanel.add(CancelBtn)
        BottomPanel.add(OkBtn)

        //// ==BottomPanel Widgets Listeners ==////
        OkBtn.addActionListener({ ActionEvent it ->
            if (ctx.getRoiStore().isEmpty()) {
                IJ.error("Error", "No ROI available. Please use the Wand Tool to select and add ROI before proceeding.")
                return
            } else {
                ctx.getRoiStore().getRois().each{Roi roi ->
                    ObjectItem currentObj = new ObjectItem(roi)
                    ctx.getObjectStore().addObject(currentObj)
                }
                empty.select()
                oManager.setDisplay(null)//清除 MASKiMAGE 上 的 roi
                MainFrame.dispose()
                latch.countDown() //通知主流程可以繼續
            }
        } as ActionListener)

        CancelBtn.addActionListener({ ActionEvent it ->
            MainFrame.dispose()
            IJ.error("Aborted", "Process aborted by user.")
            throw new RuntimeException("Aborted")
        } as ActionListener)

        return BottomPanel
    }

    def BtnSwitcher(JRadioButton Btn1, JRadioButton Btn2, List Btn1Group, List Btn2Group, MaskBrushTool Btn1tool, MaskWandTool Btn2tool, EmptyTool emptytool) {
        /// ==Button switch Controller==
        Btn1.addItemListener({ ItemEvent it ->
            if (it.stateChange == ItemEvent.SELECTED) {
                Btn2.setSelected(false)
                println("BrushBtn is work")
                Btn1Group.each { Item -> Item.setEnabled(true) }
                Btn2Group.each { Item -> Item.setEnabled(false) }
                oManager.CreateBacKG()
                oManager.setDisplay([null])
                oManager.setText([null])
                Btn1tool.select()
            } else {
                Btn1Group.each { Item -> Item.setEnabled(false) }
                emptytool.select()
            }
        } as ItemListener)

        Btn2.addItemListener({ ItemEvent e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                Btn1.setSelected(false)
                println("WandBtn is working!!")
                Btn2Group.each { Item -> Item.setEnabled(true) }
                Btn1Group.each { Item -> Item.setEnabled(false) }
                Btn2tool.select()
            } else if (e.stateChange == ItemEvent.DESELECTED) {
                Btn2Group.each { Item -> Item.setEnabled(false) }
                oManager.setDisplay([null])
                oManager.setText([null])
                emptytool.select()

            }
        } as ItemListener)

    }
}

class OverlayManager {
    private final PipelineContext ctx
    private final ImagePlus imp
    private double Opacity = 0.4
    private ImageRoi ImgRoi
    private List<Roi> DisplayRoi
    private List<TextRoi> RoiLabel
    private ImagePlus RawImg
    private boolean ShowImgRoi = true
    OverlayManager(PipelineContext ctx) {
        this.ctx = ctx
        this.imp = ctx.getRefinedMask()
        this.RawImg =ctx.getRawColorImg()
    }

    void CreateBacKG() {
        ImageProcessor Ip = ctx.getRawColorImg().getProcessor()
        ImgRoi = new ImageRoi(0, 0, Ip )
        ImgRoi.setOpacity(Opacity)
        this.ImgRoi = ImgRoi
        RefreshOverlay()
    }

    double getOpacity(){return Opacity}

    void setOpacity(double opacity){
        this.Opacity = opacity
    }

    void setShowImg(boolean ShowImgRoi){
        this.ShowImgRoi = ShowImgRoi
        RefreshOverlay()
    }

    void setDisplay( List<Roi> ObjectRoi){
        this.DisplayRoi = ObjectRoi
        RefreshOverlay()
    }

    void setText( List<TextRoi> textRoi){
        this.RoiLabel = textRoi
        RefreshOverlay()
    }

    void RefreshOverlay() {
        Overlay DisplayOverlay = new Overlay()
        if (ShowImgRoi && ImgRoi != null) {
            DisplayOverlay.add(ImgRoi)
        }
        Overlay ObjOverlay = new Overlay()
        DisplayRoi.each { item ->
            if (item != null) {
                ObjOverlay.add(item)
            }
        }

        Overlay TextOverlay = new Overlay()
        RoiLabel.each{ TextItem->
            if(TextItem != null){
                TextOverlay.add(TextItem)
            }
        }

        DisplayOverlay.add(ObjOverlay)
        DisplayOverlay.add(TextOverlay)

        ctx.getRefinedMask().setOverlay(DisplayOverlay)
        ctx.getRefinedMask().updateAndDraw()
    }
}

//region [GUI:customized tool]
class MaskWandTool extends PlugInTool{
    private int toolId = -1
    private Roi selectRoi
    Overlay DisplayOverlay
    private ImagePlus CurrentImp
    private Closure onSelect

    MaskWandTool(Closure function){
        this.onSelect = function
    }

    ImagePlus getCurrentImp(){return CurrentImp}
    Roi getSelectRoi(){return selectRoi}

    @Override
    String getToolName() { return "MaskBWand" }

    @Override
    String getToolIcon() {return "C0ffT0f18W"}

    void registrator(){
        Toolbar.addPlugInTool(this)
        def tb = Toolbar.getInstance()
        this.toolId = tb.getToolId()
    }

    void select() {
        if (toolId > 0) {
            Toolbar.getInstance().setTool(toolId)
        }
    }

    void setSelectRoi(Roi selectRoi){ this.selectRoi =selectRoi}
    @Override
    void mousePressed(ImagePlus imp, MouseEvent e) {
        Wand(imp, e)
    }
    private void Wand(ImagePlus imp, MouseEvent e) {
        ImageCanvas MaskCanvas = imp.getCanvas()
        int X = MaskCanvas.offScreenX(e.getX())
        int Y = MaskCanvas.offScreenY(e.getY())
        ImageProcessor MaskProcessor = imp.getProcessor()
        Wand wand = new Wand(MaskProcessor)
        wand.autoOutline(X, Y, 0.0, 0)
        if (wand.npoints > 0) {
            Roi CurrentRoi = new PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints, Roi.POLYGON)
            CurrentRoi.setColor(new Color(0, 220, 255))
            CurrentRoi.setStrokeWidth(3 as float)
            CurrentRoi.setHandleSize(0)
            this.selectRoi = CurrentRoi
            //IJ.log("186")
            this.CurrentImp = imp

            if( onSelect != null){
                onSelect.call(imp, CurrentRoi)
            }
        }
    }
}
class MaskBrushTool extends PlugInTool{
    private int toolId =-1
    private int Xprev = -1
    private int Yprev = -1
    private boolean blackBrush = true
    private int Diameter = 25
    @Override
    String getToolName() {return "MaskBrush"}
    @Override
    String getToolIcon() {return "000T0f18B"}

    void registrator() {
        Toolbar.addPlugInTool(this)
        def tb = Toolbar.getInstance()
        this.toolId = tb.getToolId()
    }

    void select() {
        if(toolId > 0 ) {
            Toolbar.getInstance().setTool(toolId)
        }
    }
    void setDiameter(int newBrush){
        this.Diameter = newBrush
    }

    void setColor(boolean isBlack){
        this.blackBrush = isBlack
    }

    @Override
    void mousePressed(ImagePlus imp, MouseEvent e){
        painter(imp, e)
    }

    @Override
    void mouseDragged(ImagePlus imp, MouseEvent e){
        painter(imp, e)
    }

    @Override
    void mouseReleased(ImagePlus imp, MouseEvent e){
        Xprev = -1
        Yprev = -1
    }

    private void painter(ImagePlus imp, MouseEvent e) {
        ImageCanvas MaskCanvas = imp.getCanvas()
        int X = MaskCanvas.offScreenX(e.getX())
        int Y = MaskCanvas.offScreenY(e.getY())
        ImageProcessor ip = imp.getProcessor()
        ip.setColor(blackBrush? 255:0 )
        if (Xprev < 0 ){
            ip.fillOval( X - (Diameter >>1), Y-(Diameter >>1),  Diameter, Diameter)
            Xprev = X
            Yprev = Y
            imp.updateAndDraw()
            return
        }
        double dX = X - Xprev
        double dY = Y - Yprev
        double dist = Math.sqrt(dX*dX + dY*dY)
        double step = Diameter * 0.7
        double t = step
        while (t < dist){
            int iX = (int)(Xprev + dX * (t/dist))
            int iY = (int)(Yprev + dY * (t/dist))
            ip.fillOval(iX - (Diameter >>1), iY - (Diameter>>1), Diameter, Diameter)
            t += step
        }
        ip.fillOval( X - (Diameter >>1), Y - (Diameter >>1), Diameter, Diameter)
        Xprev = X
        Yprev = Y
        imp.updateAndDraw()

    }
}

class EmptyTool extends PlugInTool{
    private int toolId = -1
    @Override
    String getToolName() {return "Empty"}

    @Override
    String getToolIcon() {return "C00fT0f18Null" }

    void registrator() {
        Toolbar.addPlugInTool(this)
        def tb = Toolbar.getInstance()
        this.toolId = tb.getToolId()
        //IJ.log("EmptyTool is registered"+ toolId)
    }

    void select() {
        if (toolId > 0) {
            Toolbar.getInstance().setTool(toolId)
            //IJ.log("Select null tool")
        }
    }
}
//endregion

