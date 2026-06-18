# Retina-layer-Gauge
**RetinaGauge** is an ImageJ tool designed for retina layer thickness measurement. By integrating **boundary restoration** and **automated edge verification**, it overcomes segmentation limitations, complemented by an interactive GUI for anatomical boundary editing and targeted layer measurement.
## Problem & Motivation：
Beyond its role in vision, retina  is considered extension of the central nervous system. Structural alterations in retinal layers has been widely investigated as biomarkers for neuroinflammation and neurodegenerative diseases. 
Among various structure quantification indicators, the layer thickness is one of the most widely used indicators. However, preThe accuracy of thickness measurement relies on precise segmentation. However, defining the layer boundaries in histological images is difficult because of sample damage, structural discontinuity and staining artifacts. Therefore, we developed RetinaGauge to restore anatomically accurate retinal layer boundaries, increaing the reliability of measurements. 
在評估與量化生物組織結構的各項參數中，分層厚度因為具備直觀性與量測便利性，已成為最常用的核心指標之一。然而，厚度量測的可靠度依賴於精確的影像分割。在實際的組織學影像中，樣本往往因組織受損、邊界不連續或染色不均等非預期原因，導致分層辨識困難。因此我們開發RetinaGauge 還原符合解剖學意義的視網膜分層邊界，提升量測的可靠性。

Retinal layer thickness is an important quantitative indicator for assessing structural alterations associated with retinal development, degeneration, and disease progression. Reliable thickness measurements are therefore essential for many pathological and experimental studies.

However, accurate delineation of retinal layers from H&E-stained sections remains challenging. Variations in staining intensity, tissue preparation artifacts, and local structural disruption frequently result in fragmented or poorly defined layer boundaries. As a consequence, conventional segmentation approaches often fail to produce anatomically meaningful retinal structures suitable for thickness analysis.

Traditionally, researchers rely on manual line measurements to estimate retinal thickness. While straightforward, this approach is labor-intensive, susceptible to user bias, and may not adequately represent the complex geometry of retinal layers.

To address these limitations, RetinaGauge was developed to reconstruct disrupted retinal layer boundaries prior to thickness measurement. By restoring structural continuity and incorporating quality-control mechanisms, the workflow aims to generate more reliable and anatomically meaningful thickness measurements from imperfect retinal images.



## Method Overview
## Step-by-step demo
## Example Results
## Limitations
## Citation
