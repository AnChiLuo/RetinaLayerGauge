# Retina-layer-Gauge
**RetinaGauge** is an ImageJ tool designed for retina layer thickness measurement. By integrating **boundary restoration** and **automated edge verification**, it overcomes segmentation limitations, complemented by an interactive GUI for anatomical boundary editing and targeted layer measurement.
## Problem & Motivation：
As the extension of the central nervous system, the retina provides valuable structural information for studying neuroinflammation and neurodegenerative disease. Among various structural quantification metrics, layer thickness is one of the most widely used indicators. However, accurate thickness measurement in histological sections remains challenging due to sample damage, discontinuous cellular distributions, and staining artifacts. RetinaGauge was developed to restore anatomically accurate retinal layer boundaries, increasing the reliability of measurements. 
## Method Overview 
RetinaGauge estimates retinal layer thickness by reconstructing anatomically plausible layer boundaries basedon segmentated nuclear mask. The workflow consist of  five major steps:   
1. **Nuclear Mask Extraction**-Extract the nuclear region within the retinal layer.
2. **Mask Reconstruction-Restore** continuous layer boundaries from fragmented structures.
3. **Boundary Review & Region Selection**-Interactively refine reconstructed boundaries and select the target region.
4. **Centerline Analysis**-Extract Centerline and perform normal valdiation and purning.
5. **Thickness Measurement**-Measure the local thickness along the validated centerline and generate quantitative outputs.
<img width="1696" height="462" alt="WorkFlow" src="https://github.com/user-attachments/assets/3a3e719b-e2e4-4007-ab9c-ed736101606a" />  
<p align="center"><em><b>Figure 1. RetinaGauge workflow for retinal layer thickness measurement.</b></em></p>

## Step-by-step demo  
### Step 1: Launch the tool 
1. Open Fiji
2. Go to `Plugins > Macros > Run...`
3. Select `RetinaLayerGauge_architecture_v6_Github.groovy`
4. Run > Run or use Ctrl+R (⌘+R on macOS)
### Step 2: Input image & options  
1.  Select images for each dataset by using <kbd>Browse</kbd> on the pop-out dialog (Fig. 2).
2.  Set the scale factor if need (Fig 2.).
<p align="left">
  <img src="docs/Fig2Loadimg.jpg" width="350">
</p>  
<p align="left"><em><b>Figure 2. Dialog for input image.</b></em></p>  

### Step 3: Determine Nuclear size 
1. Draw a line across a repersenttative nuclus to estimate the nuclear diameter(Fig. 3).
<p align="left">
  <img src="docs/Fig3EstimateNuclearSize.jpg" width="450">
</p>

<p align="leftr">
  <em><b>Figure 3. Estimation of representative nuclear diameter using the Fiji line tool..</b></em>
</p>   

   > **Why is this required?**  
   > The measured diameter is used to constrain gap repair and mask reconstruction.
### Step 4: Threshold the image and create mask  
1. You will be asked to confirm the threshold used for nuclear mask generation.
<p align="left">
  <img src="docs/Fig4Threshold.jpg" width="550">
</p>

<p align="leftr">
  <em><b>Figure 4. Fine-tune the threshold value for nuclear segmentation.</b></em>
</p>   

### Step 5: Boundary Review & Editing  
1. Click the `Enable Brush`button to active Brush tool.
   - To connect fragmened boundary use <kbd>Connect Object</kbd>.
   - To split touching objects use <kbd>Split Object</kbd>.
   - Brush size can be adjusted by `Brush size slider`.
   - Adjust raw image transparency for better boundary inspection.
<p align="left">
<img src="docs/Fig5GUI_brush.png" width="650">
</p>
<p align="left">
<em><b>Figure 5. Boundary Review & Editing GUI.</b></em>
</p>   

### Step 6: Region Selection  
1. Click the `Enable Wand`button to active Wand tool.  
   - Click a target region to automatically select the entire region (Fig. 6-Left).  
   - Press <kbd>Add</kbd> to add the selected object to the `ROI list` (Fig. 6-Right).
   - To remove object from the list, select it and press <kbd>Delete</kbd>.
   - Return to the Brush mode if further boundary refinement is required.
     
<p align="left">
<img src="docs/Fig6GUI_Wand.png" width="500" >
<img src="docs/Fig7GUI_Wand2.png" width="500">
</p>
<p align="left">
<em><b> Figure 6. Region selection workflow using the Wnad tool.</b><br>  
&nbsp;&nbsp;<b>Left:</b> Select an object by clicking on the target region.&nbsp;&nbsp;<b>Right:</b> Add selected objects to the ROI list for further processing and analysis.
</em>
</p>














## Example Results
## Limitations
## Citation
