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
*Figure 1. RetinaGauge workflow for retinal layer thickness measurement.*


## Step-by-step demo
## Example Results
## Limitations
## Citation
