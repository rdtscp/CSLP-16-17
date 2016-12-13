import java.util.*;

class areaMapMatrix {
	
	public int currTime;

	public int areaIdx;
	public float serviceFreq;
	public float thresholdVal;
	public int noBins;
	public int[][] roadsLayout;
	public float binVolume;
	public float disposalDistrRate;
	public int disposalDistrShape;
	public float bagVolume;
	public float bagWeightMin;
	public float bagWeightMax;
	public float lorryVolume;
	public float lorryMaxLoad;
	public int binServiceTime;
	public ArrayList<bin> binList = new ArrayList<bin>();
	public binLorry lorry;
	public int serviceInterval;
	public float warmUpTime;
	public float stopTime;

	public ArrayList<Integer> binsNeedingServiced 	= new ArrayList<Integer>();
	public ArrayList<Integer> bruteForceService 	= new ArrayList<Integer>();
	public ArrayList<Integer> dynamicService		= new ArrayList<Integer>();
	public boolean bruteForcing;

	// Used for calculating brute force.
	public int[][] fwRoadsLayout;
	public ArrayList<serviceList> possibleRoutes	= new ArrayList<serviceList>();

	// For tracking state of service/lorry.
	public boolean bruteForceInterrupted;
	public boolean canDepart;
	public boolean readyForNextService;
	public boolean goToDepotMidService;
	public int servicesCompleted;

	// For tracking events.
	public int timeOfArrival;
	public int timeOfDeparture;
	
	public areaMapMatrix(int currTime, int areaIdx, float serviceFreq, float thresholdVal, int noBins, int roadsLayout[][], float  binVolume, float disposalDistrRate, int disposalDistrShape, float bagVolume, float bagWeightMin, float bagWeightMax, float lorryVolume, float lorryMaxLoad, int binServiceTime, float warmUpTime, float stopTime) {
		this.currTime			= currTime;
		this.areaIdx 			= areaIdx;
		this.serviceFreq 		= serviceFreq;
		this.thresholdVal 		= thresholdVal;
		this.noBins 			= noBins;
		this.roadsLayout 		= roadsLayout;
		this.binVolume			= binVolume;
		this.disposalDistrRate	= disposalDistrRate;
		this.disposalDistrShape	= disposalDistrShape;
		this.bagVolume			= bagVolume;
		this.bagWeightMin		= bagWeightMin;
		this.bagWeightMax		= bagWeightMax;
		this.lorryVolume		= lorryVolume;
		this.lorryMaxLoad		= lorryMaxLoad;
		this.binServiceTime		= binServiceTime;
		this.warmUpTime			= warmUpTime;
		this.stopTime			= stopTime;
		setUpBins();
		this.lorry 				= new binLorry(areaIdx, roadsLayout, lorryVolume, lorryMaxLoad, binServiceTime, serviceFreq, noBins);
		this.fwRoadsLayout 		= floydWarshallRoads(); // Will create a referencable NxN matrix where element (i,j) is the shortest path from bin i to bin j
		this.serviceInterval	= (int)(3600.0/serviceFreq); 	// How long in seconds between services.
	
		// Initialise this so that first departure happens at time of first service, since we know that no bins will be serviceable at time = 0.
		this.timeOfDeparture 	= currTime + this.serviceInterval;
		this.timeOfArrival		= this.timeOfDeparture + 1; // Make sure that the first event a lorry can trigger is a departure event.

		this.readyForNextService 	= true;
		this.goToDepotMidService	= false;
		this.canDepart 				= false;

		this.servicesCompleted 	= 0;
	}



	// Used to pass info to Simulator.java
	// Returns time until lorry arrives at bin.
	public int timeUntilNextArrival(int time) {
		return this.timeOfArrival - time;
	}
	// Returns time until lorry leaves current bin.
	public int timeUntilNextDeparture(int time) {
		//recalculateTimeOfDeparture(time);
		return this.timeOfDeparture - time;
	}
	// Will update system to tell it if the bin lorry can depart for service or not.
	public void checkIfCanDepart() {
		ArrayList<Integer> binsNeedingServiced = binsOfNextService();
		if (binsNeedingServiced.size() < 1) {
			this.canDepart = false;
		}else{
			this.canDepart = true;
		}
	}

	// Used to update simulation.
	// Update system upon lorry departuring binNumber.
	public void lorryDeparted(int time, int departBin) {
		this.currTime 	= time;
		float dptBinWeight;
		float dptBinVolume;
		if (!canDepart) {
			checkIfCanDepart();
			if (!canDepart) {
				this.timeOfDeparture	= this.timeOfDeparture + serviceInterval;
				this.timeOfArrival		= this.timeOfDeparture + 1;
				return; 
			}
		}
		if (goToDepotMidService) {
			this.timeOfArrival = timeFrom(departBin, 0);
			this.timeOfDeparture = 999999999;
			lorry.departedBin(departBin, 0, 0, 0);
			return;
		}
		// If leaving depot.
		if (departBin == 0) {
			dptBinWeight = 0;
			dptBinVolume = 0;
			// If its start of a new service
			if (readyForNextService) {
				// Mark that we have started a new service and are not ready for a new one.
				readyForNextService = false;
				setUpService();
			}
		// If leaving regular bin.
		}else {
			dptBinWeight = getBin(departBin).currWeight;
			dptBinVolume = getBin(departBin).currVol;
			getBin(departBin).serviceBin();
		}
		// Get next bin to go to.
		int nextBin = nextBin(departBin);
		// Update lorry telling it where to go and how much waste its collected from last bin.
		lorry.departedBin(departBin, dptBinWeight, dptBinVolume, nextBin);
		// Update system with next event times.
		this.timeOfArrival		= this.currTime + timeFrom(departBin, nextBin);
		this.timeOfDeparture 	= 999999999; // Lorry just departed, wont be departed again before arriving. This value gets overwritten anyway.
	}
	// Update system upon lorry arrival at binNumber.
	public void lorryArrived(int time, int arriveBin) {
		// By default update system so that if its a regular bin, the currTime and departure time are updated.
		this.currTime 			= time;
		this.timeOfDeparture 	= this.currTime + binServiceTime;
		this.timeOfArrival 		= 999999999;
		lorry.lastBin = arriveBin;
		// Now do checks for special circumstances like [Bin will exceed lorry capacity, End of schedule].
		if (bruteForcing) {
			//if either (lorryvol + currbinvol) or (lorryweight + currbinweight) exceeds their threshold
			if (((lorry.currWeight + getBin(arriveBin).currWeight) > lorry.lorryMaxLoad) || ((lorry.currVolume + getBin(arriveBin).currVol) > lorry.lorryVolume)) {
				// This bin will overflow lorry
				// Update departure time to be instantaneous.
				// Raise flags that lorry should return to depot and that bruteForce was interrupted.
				this.timeOfDeparture 		= this.currTime;
				this.bruteForceInterrupted 	= true;
				this.goToDepotMidService 	= true;
			}
			// If we are arriving at depot at end of schedule.
			if (arriveBin == 0 && bruteForceService.size() < 1) {
				// 	TODO: Update system with end of schedule results.
				this.readyForNextService = true;
				this.servicesCompleted++;
				this.timeOfDeparture = this.currTime + delayToNextService();
				this.canDepart = false;
			}
			// If we are arriving at depot mid service.
			if (arriveBin == 0 && bruteForceService.size() > 0) {
				goToDepotMidService = false;
				this.timeOfDeparture = this.currTime + 5*binServiceTime;
				lorry.currVolume = 0;
				lorry.currWeight = 0;
				// TODO: Update brute force schedule.
				ArrayList<Integer> remainingBins = bruteForceService;
				remainingBins.remove(remainingBins.size() - 1);
				this.bruteForceService = createBruteForceService(remainingBins);
			}
		}else{
			if (((lorry.currWeight + getBin(arriveBin).currWeight) > lorry.lorryMaxLoad) || ((lorry.currVolume + getBin(arriveBin).currVol) > lorry.lorryVolume)) {
				// This bin will overflow lorry
				// Update departure time to be instantaneous.
				// Raise flags that lorry should return to depot and that bruteForce was interrupted.
				this.timeOfDeparture 		= this.currTime;
				this.goToDepotMidService 	= true;
			}
			// If we have arrived at last bin in dynamicService, tell it to return to depot.
			if (arriveBin != 0 && dynamicService.size() < 1) {
				dynamicService.add(0);
			}
			// If we are arriving at depot mid service.
			if (arriveBin == 0 && dynamicService.size() > 0) {
				goToDepotMidService = false;
				this.timeOfDeparture = this.currTime + 5*binServiceTime;
				lorry.currVolume = 0;
				lorry.currWeight = 0;
			}
			if (arriveBin == 0 && dynamicService.size() < 1) {
				//Set up for next schedule
				this.readyForNextService = true;
				this.servicesCompleted++;
				this.timeOfDeparture = this.currTime + delayToNextService();
				this.canDepart = false;
			}

		}
	}
	// Returns next bin to be visited and updates system
	public int nextBin(int lastBin) {
		int output;
		if (bruteForcing) {
			// output = next planned bin.
			if (goToDepotMidService) {
				goToDepotMidService = false;
				return 0;
			}
			/*
			for (int i = 0; i < bruteForceService.size(); i++) {
				System.out.println("Schedule bin " + i + " = " + bruteForceService.get(i));
			}
			*/
			output = bruteForceService.get(0);
			bruteForceService.remove(0);
			/*
			System.out.println();
			for (int i = 0; i < bruteForceService.size(); i++) {
				System.out.println("Schedule bin " + i + " = " + bruteForceService.get(i));
			}
			*/
		}else{
			// output = next planned bin.
			if (goToDepotMidService) {
				goToDepotMidService = false;
				return 0;
			}
			output = nextNearestBin(lastBin, dynamicService);
			dynamicService.remove(Integer.valueOf(output));
		}
		return output;
	}

	// Decides if the route will be brute forced or calculated on the go and sets up bins to visit.
	public void setUpService() {
		this.binsNeedingServiced = binsOfNextService();
		if (binsNeedingServiced.size() > 10) {
			this.bruteForcing 		= true;
			this.bruteForceService 	= createBruteForceService(binsNeedingServiced);	
		}else{
			this.bruteForcing 		= false;
			this.dynamicService 	= createDynamicService(binsNeedingServiced);
		}
	}
	// Updates ArrayList with bins needing serviced.
	public ArrayList<Integer> binsOfNextService() {
		ArrayList<Integer> output = new ArrayList<Integer>();
		for (int i = 1; i <= noBins; i++) {
			if (binList.get(i).isThresholdExceeded()) {
				output.add(i);
			}
		}
		return output;
	}
	// Update bruteForceService with ordered list of optimal route.
	public ArrayList<Integer> createBruteForceService(ArrayList<Integer> binsToBeBruteForced) {
		// Init system variables.
		possibleRoutes.clear();
		int possibilities 		= fact(binsToBeBruteForced.size());
		int[] binList 			= listToArray(binsToBeBruteForced);
		// Update system with all permutations of possible routes(excluding start and end nodes.
		permuteRoutes(binList, 0);
		// Choose route with lowest cost.
		int lowestCostVal 		= 999999;
		int lowestCostPointer 	= 0;
		for (int i = 0; i < possibilities; i++) {
			ArrayList<Integer> currentRoute = possibleRoutes.get(i).toArrayList();
			currentRoute.add(0, 0);
			currentRoute.add(0);
			if (costOfRoute(currentRoute) < lowestCostVal) {
				lowestCostVal 		= costOfRoute(currentRoute);
				lowestCostPointer 	= i;
			}
		}
		possibleRoutes.get(lowestCostPointer).toArrayList().remove(0);
		return possibleRoutes.get(lowestCostPointer).toArrayList();
	}
	// Update dynamicService with list of bins needing visited.
	public ArrayList<Integer> createDynamicService(ArrayList<Integer> binsToBeDynamicd) {
		// Returns just a list of bins needing services since the next bin is chosen intelligently.
		return binsToBeDynamicd;
	}
	// Returns bin number of nearest bin to the last bin/current bin.
	public int nextNearestBin(int currBin, ArrayList<Integer> binsToBeServiced) {
		int tempNextBin = binsToBeServiced.get(0);
		for (int i=0; i < binsToBeServiced.size(); i++) {
			if (timeFrom(currBin, binsToBeServiced.get(i)) < timeFrom(currBin, tempNextBin)) {
				tempNextBin = binsToBeServiced.get(i);
			}
		}
		return tempNextBin;
	}
	// Time between two bins
	public int timeFrom(int bin1, int bin2) {
		return (fwRoadsLayout[bin1][bin2] *60*60);
	}
	// Calculates cost of running a route.
	public int costOfRoute(ArrayList<Integer> serviceRoute) {
		int output = 0;
		int size = serviceRoute.size();
		for (int i = 0; i < (size-1); i++) {
			output = output + timeFrom(serviceRoute.get(i), serviceRoute.get(i+1));
		}
		return output;
	}
	// Updates possibleRoutes will all possible routes
	// Adapted slightly from:
	// 							https://stackoverflow.com/questions/30387185/print-out-all-permutations-of-an-array
	//
	public void permuteRoutes(int[] arr, int index){
	    if(index >= arr.length - 1){ //If we are at the last element - nothing left to permute
	        serviceList nextList = new serviceList(arr);
	        this.possibleRoutes.add(nextList);
	        return;
	    }

	    for(int i = index; i < arr.length; i++){ //For each index in the sub array arr[index...end]

	        //Swap the elements at indices index and i
	        int t = arr[index];
	        arr[index] = arr[i];
	        arr[i] = t;

	        //Recurse on the sub array arr[index+1...end]
	        permuteRoutes(arr, index+1);

	        //Swap the elements back
	        t = arr[index];
	        arr[index] = arr[i];
	        arr[i] = t;
	    }
	}





	



	
	// Initialisation.
	// Initialise all bins.
	private void setUpBins() {
		for (int i = 0; i <= this.noBins; i++) {
			bin aBin = new bin(i, binVolume, disposalDistrRate, disposalDistrShape, bagVolume, bagWeightMin, bagWeightMax, thresholdVal);
			binList.add(aBin);
		}
	}
	// Create noBins x noBins matrix where element (i,j) is the shortest path from bin i to bin j.
	private int[][] floydWarshallRoads() {
		// Create noBins x noBins matrix where
		// element (i,j) is the shortest path from bin i to bin j
		int[][] fwRoads = adjustedRoadsLayout(roadsLayout);
		for (int k = 0; k < fwRoads.length; k++) {
			for (int i = 0; i < fwRoads.length; i++) {
				for (int j = 0; j < fwRoads.length; j++) {
					if (fwRoads[i][j] > (fwRoads[i][k] + fwRoads[k][j])) {
						fwRoads[i][j] = (fwRoads[i][k] + fwRoads[k][j]);
					}
				}
			}
		}
		return fwRoads;
	}
	// Initialise list of times for schedules.
	private ArrayList<Integer> setupServiceTimes() {
		ArrayList<Integer> output = new ArrayList<Integer>();
		int scheduleTime = 0;
		int numberSchedules = (int)(stopTime/serviceInterval);
		for (int i = 0; i < numberSchedules; i++) {
			scheduleTime = scheduleTime + serviceInterval;
			output.add(scheduleTime);
		}
		return output;
	}
	// Will return a 2d array where '-1' is replaced by +ve infinity
	public int[][] adjustedRoadsLayout(int[][] roadsLayout) {
		int[][] output = roadsLayout;
		for (int i = 0; i < output.length; i++) {
			for (int j = 0; j < output.length; j++) {
				if (output[i][j] == -1) {
					output[i][j] = 999999999;
				}
			}
		}
		return output;
	}



	// Supporting Functions:
	// Turns a list into a string
	public String listToString(ArrayList<Integer> input) {
		String output = "[";
		for (int i = 0; i < input.size(); i++) {
			output = output + input.get(i) + ", ";
		}
		output = output + "]";
		return output;
	}
	// Returns bin binNumber.
	public bin getBin(int binNumber) { // Returns bin number binNo in area number areaNo
		bin output = binList.get(binNumber);
		return output;
	}
	// Will this bin exceed lorrys capacity
	public boolean willExceedLorry(int binNumber) {
		if ( ((lorry.currWeight + getBin(binNumber).currWeight) > lorry.lorryMaxLoad) || ((lorry.currVolume + getBin(binNumber).currVol) > lorry.lorryVolume) ) {
			return true;
		}else{
			return false;
		}
	}
	// Returns factorial value of an int.
	public int fact(int fact) {
		int output = 1;
		for (int i = 1; i <= fact; i++) {
			output = output * i;
		}
		return output;
	}
	// Takes an ArrayList<Integer> and returns equivalent int[]
	public int[] listToArray(ArrayList<Integer> input) {
		int size = input.size();
		int[] output = new int[size];
		for (int i = 0; i < size; i++) {
			output[i] = input.get(i);
		}
		return output;
	}
	// Returns time to next departure/service.
	public int delayToNextService() {
		int offset = (this.serviceInterval * (this.servicesCompleted + 1)) - this.currTime;
		if (offset >= 0) {
			return offset;
		}else{
			return 0;
		}
	}



	// Testing.
	// Returns string map of this area (used for testing).
	public String toString() {
		String output = "";
		for (int i=0; i <= this.noBins; i++) {
			for (int j=0; j <= this.noBins; j++) {
				if (roadsLayout[i][j] >= 0 && roadsLayout[i][j] <= 9) {
					output = output + "  " + roadsLayout[i][j];
					if (j == this.noBins) { output = output + "\n"; }
				}
				else{
					output = output + " " + roadsLayout[i][j];
					if (j == this.noBins) { output = output + "\n"; }
				}
			}
		}
		return output;
	}
}