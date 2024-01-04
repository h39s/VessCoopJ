
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;

import ij.io.FileSaver;
import ij.io.OpenDialog;  
import ij.io.DirectoryChooser;

import ij.gui.Roi;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;

import ij.plugin.PlugIn;
import ij.plugin.ChannelSplitter; 
import ij.plugin.ImageCalculator;
import ij.plugin.frame.RoiManager;
import ij.plugin.RGBStackMerge;
import ij.plugin.ZProjector;
import ij.process.ImageConverter;

import ij.measure.Calibration;
import ij.measure.ResultsTable; 

import trainableSegmentation.*; 

import java.io.File;
import java.awt.Point; 



public class VessCoopJ_V4 implements PlugIn {

	@Override
	public void run(String arg) {

		// SELECT BLOOD VESSEL CHANNEL AND MIN SLICE
		boolean globalBloodVesselImage = false;
		NonBlockingGenericDialog findBloodVesselImage = new NonBlockingGenericDialog("Blood Vessel Channel Selection");
		findBloodVesselImage.addMessage("1. Please select the blood vessel channel, and");
		findBloodVesselImage.addMessage("2. Scroll to the first slice where the vessels are clearly visible and distinguishable from a dark background.");
		findBloodVesselImage.addMessage("3. If you'd like to use the same channel and slice for all images, check the box below.");
		findBloodVesselImage.addCheckbox("Save blood vessel slice selection for all images", true);
		findBloodVesselImage.hideCancelButton();
		int bloodVesselChannel = 0;
		int minSlice = 0;
		
		  /////////////////////////////////////////
		 // TRAIN A NEW BLOOD VESSEL CLASSIFIER //
		/////////////////////////////////////////
		GenericDialog vesselClassifier = new GenericDialog("Train vessel classifier?");
		vesselClassifier.addMessage("Would you like to train a blood vessel new classifier?");
		vesselClassifier.setOKLabel("Train new classifier");
		vesselClassifier.setCancelLabel("Use saved classifier");
		vesselClassifier.showDialog();

		if (vesselClassifier.wasOKed()) {
			// OPEN FOLDER
			String folderPath = new DirectoryChooser("Select a folder of training images").getDirectory();
			// GenericDialog fileExtension = new GenericDialog("Image Extension");
			// fileExtension.addStringField("Please enter the extension/filetype of your images", ".nd2");
			// fileExtension.hideCancelButton();
			// fileExtension.showDialog();
			String fileExtensionString = ".nd2";

			// CREATE BLOOD VESSEL STACK - THIS WILL BE USED TO TRAIN THE CLASSIFIER
			ImageStack bloodVesselStack = null;
			File folder = new File(folderPath);
			File[] imageFiles = folder.listFiles();
			for (File img : imageFiles) {

				String imagePath = img.getAbsolutePath();
				if (!imagePath.endsWith(fileExtensionString)) {
					continue;
				}
				IJ.run("Bio-Formats Importer" , "open=["+imagePath+"] color_mode=Default rois_import=[ROI manager] view=Hyperstack stack_order=XYCZT");
				ImagePlus image = IJ.getImage();

				// SELECT BLOOD VESSEL SLICES 
				if (!globalBloodVesselImage) {
					findBloodVesselImage.showDialog();
					bloodVesselChannel = image.getChannel();
					minSlice = image.getZ();
					if (findBloodVesselImage.getNextBoolean()) {
						globalBloodVesselImage = true;
					}
					image.hide();
				} else {
					image.hide();
				}
				
				ImagePlus[] splitChannels = ChannelSplitter.split(image); 
				image.close();
				ImagePlus bloodVesselSlices = splitChannels[bloodVesselChannel-1];
				ImagePlus bloodVessels = ZProjector.run(bloodVesselSlices,"sum",minSlice,bloodVesselSlices.getNSlices());

				// CALCULATE BLOOD VESSEL AREA
				bloodVessels.show();

				// PREPROCESSING STEPS
				// IJ.run(bloodVessels, "Subtract Background...", "rolling="+ Math.max(bloodVessels.getWidth(), bloodVessels.getHeight()));
				IJ.run(bloodVessels, "Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=255 maximum=3 mask=*None*");
				IJ.run(bloodVessels, "Remove Outliers...", "radius=1 threshold=0 which=Bright");

				if (bloodVesselStack == null) {
					bloodVesselStack = bloodVessels.createEmptyStack();
				}
				bloodVesselStack.addSlice(bloodVessels.getProcessor());

				bloodVessels.hide();
			}
			ImagePlus bloodVessels = new ImagePlus("Blood Vessels", bloodVesselStack);
			bloodVessels.show();

			IJ.setTool("freehand");
			IJ.run("Trainable Weka Segmentation");
			NonBlockingGenericDialog train = new NonBlockingGenericDialog("Classifier training");
			train.addMessage("To train a classifier:");
			train.addMessage("1. Select samples of blood vessels by circling only blood vessel pixels, then clicking the 'Add to class 1' button.");
			train.addMessage("2. Select samples of the background by circling only background pixels, then clicking 'Add to class 2' button.");
			train.addMessage("3. Click the 'Train classifier' button.");
			train.addMessage("4. Click the 'Save classifier' button. NOTE: Wait until the buttons are no longer greyed out.");
			train.addMessage("5. Close the Weka Segmenation Window ONLY once the classifier is saved.");
			train.addMessage("Press OK once the classifier is saved and the Weka Segmentation Window is closed.");
			train.hideCancelButton();
			train.showDialog();

			if (train.wasOKed()) {
				bloodVessels.hide();
			}
		}

		// LOAD SAVED CLASSIFIER - PREVIOUSLY TRAINED
		String vesselClassifierPath = new OpenDialog("Select a saved blood vessel classifier model").getPath();


		// SELECT FIRST CHANNEL FOR CELLS
		boolean globalCellImage1 = false;
		String cellChannel1Name = "Cell Channel 1";
		NonBlockingGenericDialog findCellImage1 = new NonBlockingGenericDialog("Cell Channel 1 Selection");
		findCellImage1.addMessage("Please select the first channel for cells and press OK.");
		findCellImage1.addCheckbox("Save cell channel 1 selection for all images", true);
		findCellImage1.addMessage("You can name this cell channel for saving results.");
		findCellImage1.addStringField("Cell Channel 1 Name: ", cellChannel1Name);
		findCellImage1.hideCancelButton();
		int cellChannel1 = 0;


		// SELECT SECOND CHANNEL FOR CELLS
		boolean globalCellImage2 = false;
		String cellChannel2Name = "Cell Channel 2";
		NonBlockingGenericDialog findCellImage2 = new NonBlockingGenericDialog("Cell Channel 2 Selection");
		findCellImage2.addMessage("Please select the second channel for cells and press OK.");
		findCellImage2.addCheckbox("Save cell channel 2 selection for all images", true);
		findCellImage2.addMessage("You can name this cell channel for saving results.");
		findCellImage2.addStringField("Cell Channel 2 Name: ", cellChannel2Name);
		findCellImage2.hideCancelButton();
		int cellChannel2 = 0;


		// ASK USER IF THEY WANT TO CLASSIFY CELLS
		boolean classifyCells = false;
		GenericDialog classify = new GenericDialog("Classify cells?");
		classify.addMessage("Would you like to classify cells?");
		classify.setOKLabel("Classify cells");
		classify.setCancelLabel("Use thresholding");
		classify.showDialog();
		WekaSegmentation cellSegmentor = null;
		String cellClassifierPath = null;

		if (classify.wasOKed()) {
			classifyCells = true;

			/////////////////////////////////
			// TRAIN A NEW CELL CLASSIFIER //
			/////////////////////////////////
			GenericDialog cellClassifier = new GenericDialog("Train cell classifier?");
			cellClassifier.addMessage("Would you like to train a new cell classifier?");
			cellClassifier.setOKLabel("Train new classifier");
			cellClassifier.setCancelLabel("Use saved classifier");

			cellClassifier.showDialog();
			if (cellClassifier.wasOKed()) {
				// OPEN FOLDER
				String folderPath = new DirectoryChooser("Select a folder of training images").getDirectory();
				// GenericDialog fileExtension = new GenericDialog("Image Extension");
				// fileExtension.addStringField("Please enter the extension/filetype of your images", ".nd2");
				// fileExtension.hideCancelButton();
				// fileExtension.showDialog();
				String fileExtensionString = ".nd2";

				// CREATE CELL STACK - THIS WILL BE USED TO TRAIN THE CLASSIFIER
				ImageStack cellStack = null;
				File folder = new File(folderPath);
				File[] imageFiles = folder.listFiles();
				for (File img : imageFiles) {

					String imagePath = img.getAbsolutePath();
					if (!imagePath.endsWith(fileExtensionString)) {
						continue;
					}
					IJ.run("Bio-Formats Importer" , "open=["+imagePath+"] color_mode=Default rois_import=[ROI manager] view=Hyperstack stack_order=XYCZT");
					ImagePlus image = IJ.getImage();

					// SELECT BLOOD VESSEL CHANNEL AND MIN SLICE
					if (!globalBloodVesselImage) {
						findBloodVesselImage.showDialog();
						bloodVesselChannel = image.getChannel();
						minSlice = image.getZ();
						if (findBloodVesselImage.getNextBoolean()) {
							globalBloodVesselImage = true;
						}
						if (globalCellImage1 && globalCellImage2) {
							image.hide();
						}
					}
					// SELECT FIRST CHANNEL FOR CELLS
					if (!globalCellImage1) {
						findCellImage1.showDialog();
						cellChannel1 = image.getChannel();
						cellChannel1Name = findCellImage1.getNextString();
						if (findCellImage1.getNextBoolean()) {
							globalCellImage1 = true;
						}
						if (globalCellImage2) {
							image.hide();
						}
					}
					// SELECT SECOND CHANNEL FOR CELLS
					if (!globalCellImage2) {
						findCellImage2.showDialog();
						cellChannel2 = image.getChannel();
						cellChannel2Name = findCellImage2.getNextString();
						if (findCellImage2.getNextBoolean()) {
							globalCellImage2 = true;
						}
						image.hide();
					}
					
					// SPLIT CHANNELS
					ImagePlus[] splitChannels = ChannelSplitter.split(image); 
					image.close();
					ImagePlus cellSlices1 = splitChannels[cellChannel1-1];
					ImagePlus cellSlices2 = splitChannels[cellChannel2-1];

					// MAX PROJECT ALL SLICES ABOVE MIN SLICE
					ImagePlus cells1 = ZProjector.run(cellSlices1,"max",minSlice,cellSlices1.getNSlices());
					ImagePlus cells2 = ZProjector.run(cellSlices2,"max",minSlice,cellSlices2.getNSlices());

					// MERGE CELL CHANNELS FOR THRESHOLDING AND DETECTION
					ImagePlus cells = new ImageCalculator().run("Max create", cells1, cells2);

					if (cellStack == null) {
						cellStack = cells.createEmptyStack();
					}
					cellStack.addSlice(cells.getProcessor());

					cells.hide();
				}
				ImagePlus cells = new ImagePlus("Cells", cellStack);
				cells.show();

				IJ.setTool("freehand");
				IJ.run("Trainable Weka Segmentation");
				NonBlockingGenericDialog train = new NonBlockingGenericDialog("Classifier training");
				train.addMessage("To train a classifier:");
				train.addMessage("1. Select samples of cells by circling only cell pixels, then clicking the 'Add to class 1' button.");
				train.addMessage("2. Select samples of the background by circling only background pixels, then clicking 'Add to class 2' button.");
				train.addMessage("3. Click the 'Train classifier' button.");
				train.addMessage("4. Click the 'Save classifier' button. NOTE: Wait until the buttons are no longer greyed out.");
				train.addMessage("5. Close the Weka Segmenation Window ONLY once the classifier is saved.");
				train.addMessage("Press OK once the classifier is saved and the Weka Segmentation Window is closed.");
				train.hideCancelButton();
				train.showDialog();

				if (train.wasOKed()) {
					cells.hide();
				}
			}
			// LOAD SAVED CLASSIFIER - PREVIOUSLY TRAINED
			cellClassifierPath = new OpenDialog("Select a saved cell classifier model").getPath();
		}

		
		// THRESHOLDING FOR CELLS
		boolean globalThreshold = false;
		GenericDialog thresholdDialog = new GenericDialog("Threshold");
		thresholdDialog.addMessage("Please select a threshold value for the cells. The default value is 15.");
		thresholdDialog.addMessage("Increase the threshold value if cell area is overestimated, and decrease the threshold value if cell area is underestimated.");
		thresholdDialog.addNumericField("Threshold value: ", 15);
		thresholdDialog.addMessage("Also select the radius parameter for the Bernsen method. The default value is 15.");
		thresholdDialog.addMessage("The radius should be close to the maximum cell width.");
		thresholdDialog.addNumericField("Radius: ", 15);
		thresholdDialog.addCheckbox("Save threshold value for all images", true);
		thresholdDialog.setCancelLabel("Preview thresholded cells");
		thresholdDialog.setOKLabel("Use this threshold value");
		double threshold = 15;
		double radius = 15;
		
		
		// SET MINIMUM CELL SIZE
		double minCellSize = 20;
		boolean globalMinCellSize = false;
		GenericDialog cellDialog = new GenericDialog("Cell Size");
		cellDialog.addNumericField("Minimum cell size (pixels): ", minCellSize);
		cellDialog.addCheckbox("Save minimum cell size for all images", true);


		// SET SCALE
		String units = "pixels";
		double pixelWidth = 1.0; 
		double pixelHeight = 1.0;
		boolean globalScale = false;
		NonBlockingGenericDialog scaleDialog = new NonBlockingGenericDialog("Set Scale?");
		scaleDialog.addMessage("Trace scale bar on image and press OK.");
		scaleDialog.addNumericField("Known distance" , 1.0);
		scaleDialog.addStringField("Unit of length" , units);
		scaleDialog.addCheckbox("Save scale for all images", true);
		scaleDialog.setCancelLabel("No scale");
		scaleDialog.setOKLabel("Set scale");


		// OPEN FOLDER
		String folderPath = new DirectoryChooser("Select a folder of images").getDirectory();
		// GenericDialog fileExtension = new GenericDialog("Image Extension");
		// fileExtension.addStringField("Please enter the extension/filetype of your images", ".nd2");
		// fileExtension.hideCancelButton();
		// fileExtension.showDialog();
		String fileExtensionString = ".nd2"; // fileExtension.getNextString();

		
		// SELECT OUTPUT FOLDER FOR RESULTS
		DirectoryChooser output = new DirectoryChooser("Select a folder to save results");
		DirectoryChooser.setDefaultDirectory(folderPath);
		String outputPath = output.getDirectory();


		// PROCESS EACH IMAGE IN THE FOLDER
		File folder = new File(folderPath);
		File[] imageFiles = folder.listFiles();
		for (File img : imageFiles) {
			String imagePath = img.getAbsolutePath();

			// IGNORE NON-IMAGE FILES
			if (!imagePath.endsWith(fileExtensionString)) {
				continue;
			}

			// OPEN IMAGE
			String imageName = img.getName().split(fileExtensionString)[0];
			IJ.run("Bio-Formats Importer" , "open=["+imagePath+"] color_mode=Default rois_import=[ROI manager] view=Hyperstack stack_order=XYCZT");
			ImagePlus image = IJ.getImage();

			// CHECK GLOBAL VARIABLES
			if (globalBloodVesselImage && globalCellImage1 && globalCellImage2) {
				image.hide();
			}

			// GET SCALE		
			Calibration c = image.getCalibration();

			// SET SCALE
			if (c.scaled()) {
				pixelWidth = c.pixelWidth;
				pixelHeight = c.pixelHeight;
				units = c.getUnit();

			} else if (!globalScale) {
				Roi scaleValue = null;
				IJ.setTool("line");
				scaleDialog.showDialog();
				if (scaleDialog.wasOKed()) {
					scaleValue = image.getRoi();
					if(scaleValue!=null) {
						pixelWidth = scaleDialog.getNextNumber() / scaleValue.getLength();
						units = scaleDialog.getNextString();
					}
				}
				if (scaleDialog.getNextBoolean()) {
					globalScale = true;
				}
				if (globalBloodVesselImage && globalCellImage1 && globalCellImage2) {
					image.hide();
				}
			}

			// SELECT BLOOD VESSEL CHANNEL AND MIN SLICE
			if (!globalBloodVesselImage) {
				findBloodVesselImage.showDialog();
				bloodVesselChannel = image.getChannel();
				minSlice = image.getZ();
				if (findBloodVesselImage.getNextBoolean()) {
					globalBloodVesselImage = true;
				}
				if (globalCellImage1 && globalCellImage2) {
					image.hide();
				}
			}
			// SELECT FIRST CHANNEL FOR CELLS
			if (!globalCellImage1) {
				findCellImage1.showDialog();
				cellChannel1 = image.getChannel();
				cellChannel1Name = findCellImage1.getNextString();
				if (findCellImage1.getNextBoolean()) {
					globalCellImage1 = true;
				}
				if (globalCellImage2) {
					image.hide();
				}
			}
			// SELECT SECOND CHANNEL FOR CELLS
			if (!globalCellImage2) {
				findCellImage2.showDialog();
				cellChannel2 = image.getChannel();
				cellChannel2Name = findCellImage2.getNextString();
				if (findCellImage2.getNextBoolean()) {
					globalCellImage2 = true;
				}
				image.hide();
			}
			
			// SPLIT CHANNELS
			ImagePlus[] splitChannels = ChannelSplitter.split(image); 
			image.close();
			ImagePlus bloodVesselSlices = splitChannels[bloodVesselChannel-1];
			ImagePlus cellSlices1 = splitChannels[cellChannel1-1];
			ImagePlus cellSlices2 = splitChannels[cellChannel2-1];

			// MAX PROJECT ALL SLICES ABOVE MIN SLICE
			ImagePlus bloodVessels = ZProjector.run(bloodVesselSlices,"sum",minSlice,bloodVesselSlices.getNSlices());
			ImagePlus cells1 = ZProjector.run(cellSlices1,"max",minSlice,cellSlices1.getNSlices());
			ImagePlus cells2 = ZProjector.run(cellSlices2,"max",minSlice,cellSlices2.getNSlices());


			  ////////////////////////////
			 // CELL SEGMENTATION STEP //
			////////////////////////////

			// MERGE CELL CHANNELS FOR THRESHOLDING AND DETECTION
			ImagePlus cells = new ImageCalculator().run("Max create", cells1, cells2);
			ImagePlus cellCopy = cells.duplicate();

			
			if (classifyCells) {
				// APPLY CLASSIFIER TO CELL IMAGE
				cellSegmentor = new WekaSegmentation(cells);
				cellSegmentor.loadClassifier(cellClassifierPath);
				ImagePlus result = cellSegmentor.applyClassifier(cells);
				cells.setImage(result);

			} else {
				// CONVERT TO 8-BIT, AUTO LOCAL THRESHOLD, AND WATERSHED
				ImageConverter.setDoScaling(true);
				ImageConverter ic = new ImageConverter(cells);
				ic.convertToGray8();
				IJ.run(cells, "Auto Local Threshold", "method=Bernsen radius="+radius+" parameter_1="+threshold+" parameter_2=0 white");
				IJ.run(cells, "Watershed", "");
				
				// THRESHOLDING FOR CELLS
				if (!globalThreshold) {
					// cellCopy.show();
					do {
						cells.show();
						thresholdDialog.showDialog();
						threshold = thresholdDialog.getNextNumber();
						radius = thresholdDialog.getNextNumber();
						if (thresholdDialog.getNextBoolean()) {
							globalThreshold = true;
						}
						if (thresholdDialog.wasCanceled()) {
							cells.hide();
							cells = cellCopy.duplicate();
							ic = new ImageConverter(cells);
							ic.convertToGray8();
							IJ.run(cells, "Auto Local Threshold", "method=Bernsen radius="+radius+" parameter_1="+threshold+" parameter_2=0 white");
							IJ.run(cells, "Watershed", "");
						}
					} while (thresholdDialog.wasCanceled() && !thresholdDialog.wasOKed());
					cells.hide();
					// cellCopy.hide();
				}
			}

			// CALCULATE CELL AREAS AND MAX WIDTHS
			if (!globalMinCellSize) {
				cells.show();
				cellDialog.showDialog();
				minCellSize = cellDialog.getNextNumber();
				if (cellDialog.getNextBoolean()) {
					globalMinCellSize = true;
				}
			}
			IJ.run(cells, "Analyze Particles...", "size="+minCellSize+"-Infinity pixel exclude clear add");

			// SAVE CELL ROIS
			RoiManager rm = RoiManager.getInstance();
			rm.save(outputPath + imageName + "_rois.zip");
			Roi[] cellRois = rm.getRoisAsArray();
			int numCells = rm.getCount();

			// CALCULATE CELL MAX WIDTHS
			double cellMaxWidths[] = new double[numCells];
			for(int i = 0; i < numCells; i=i+1) {
				cellMaxWidths[i] = cellRois[i].getFeretsDiameter() * pixelWidth;
			}
			cells.hide();


			  ///////////////////////////////
			 // BLOOD VESSEL SEGMENTATION //
			///////////////////////////////

			ImagePlus bloodVesselCopy = bloodVessels.duplicate();
			bloodVessels.show();

			// PREPROCESSING STEPS
			// IJ.run(bloodVessels, "Subtract Background...", "rolling="+ Math.max(bloodVessels.getWidth(), bloodVessels.getHeight()));
			IJ.run(bloodVessels, "Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=255 maximum=3 mask=*None*");
			IJ.run(bloodVessels, "Remove Outliers...", "radius=1 threshold=0 which=Bright");

			// APPLY CLASSIFIER TO BLOOD VESSEL IMAGE
			WekaSegmentation vesselSegmentor = new WekaSegmentation(bloodVessels);
			vesselSegmentor.loadClassifier(vesselClassifierPath);
			ImagePlus result = vesselSegmentor.applyClassifier(bloodVessels);
			bloodVessels.setImage(result);

			// POSTPROCESSING STEPS
			IJ.run(bloodVessels, "Multiply...", "value=2");
			IJ.setThreshold(bloodVessels, 0, 1);
			IJ.run(bloodVessels, "Convert to Mask", "");

			// COMBINE RESULTS AND CALCULATE AREA OF EACH CELL OVERLAPPING WITH BLOOD VESSEL
			ImagePlus overlapping = new ImageCalculator().run("AND create", cells, bloodVessels);

			// APPLY COLOR TO CHANNELS FOR VISUALIZATION
			IJ.run(bloodVessels, "Red", "");
			IJ.run(cells, "Green", "");
			IJ.run(overlapping, "Blue", "");

			// MERGE AS RGB IMAGE AND SAVE
			ImagePlus[] imageArray = new ImagePlus[]{ bloodVessels, cells, overlapping };
			ImagePlus mergedOverlap = RGBStackMerge.mergeChannels(imageArray, false);
			FileSaver fsOverlap = new FileSaver(mergedOverlap);
			fsOverlap.saveAsTiff(outputPath + imageName + "_overlap.tif");

			// ALSO MERGE RAW IMAGES FOR COMPARISON
			ImagePlus[] imageCopy = new ImagePlus[]{ bloodVesselCopy, cells1, cells2 };
			ImagePlus mergedCopy = RGBStackMerge.mergeChannels(imageCopy, false);
			FileSaver fsCopy = new FileSaver(mergedCopy);
			fsCopy.saveAsTiff(outputPath + imageName + "_copy.tif");

			// CALCULATE CELL-VESSEL OVERLAP AND META DATA
			double cellVesselOverlap[] = new double[numCells];
			double cellAreas[] = new double[numCells];
			double percentageOverlap[] = new double[numCells];
			double averageIntensity1[] = new double[numCells];
			double averageIntensity2[] = new double[numCells];
			for(int i = 0; i < numCells; ++i) {
				Roi currCell = cellRois[i];
				Point[] roiPoints = currCell.getContainedPoints();
				double currCellVesselOverlap = 0;
				double totalCell = 0;
				double totalIntensity1 = 0;
				double totalIntensity2 = 0;
				for (Point p: roiPoints) {
					totalCell += 1;
					if (overlapping.getPixel(p.x, p.y)[0] > 0) {
						currCellVesselOverlap += 1;
					}
					totalIntensity1 += cells1.getPixel(p.x, p.y)[0];
					totalIntensity2 += cells2.getPixel(p.x, p.y)[0];
				}
				cellVesselOverlap[i] = (double)currCellVesselOverlap * pixelWidth * pixelHeight;
				cellAreas[i] = (double)totalCell * pixelWidth * pixelHeight;
				percentageOverlap[i] = (cellVesselOverlap[i] / cellAreas[i]) * 100;
				averageIntensity1[i] = totalIntensity1 / totalCell;
				averageIntensity2[i] = totalIntensity2 / totalCell;
			}
			
			// CLOSE IMAGES
			cells.changes = false; 
			cells.close();
			overlapping.changes = false;
			overlapping.close();
			bloodVessels.changes = false; 
			bloodVessels.close();
			
			// PRINT RESULTS (CELL MAX WIDTHS AND AREA OVERLAPPING WITH BLOOD VESSELS)
			ResultsTable results = new ResultsTable();
			for(int i = 0; i < numCells; i++) {
				results.addValue("Maximum Cell Width ("+units+")", cellMaxWidths[i]);
				results.addValue("Cell-Vessel Overlap ("+units+"^2)", cellVesselOverlap[i]);
				results.addValue("Total Cell Area ("+units+"^2)", cellAreas[i]);
				results.addValue("% of Cell Area Overlapping with Vessel", percentageOverlap[i]);
				results.addValue("Average Intensity in "+cellChannel1Name+" (per pixel)", averageIntensity1[i]);
				results.addValue("Average Intensity in "+cellChannel2Name+" (per pixel)", averageIntensity2[i]);
				results.addRow();
			}
			results.showRowNumbers(true);
			results.save(outputPath + imageName + "_results.csv");
		}
	}
}

