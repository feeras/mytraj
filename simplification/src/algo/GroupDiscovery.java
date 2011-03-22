package algo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.commons.collections.CollectionUtils;
import org.joda.time.LocalTime;

import trie.LeafEntry;
import trie.Trie;
import data.Data;
import data.DataPoint;
import entity.Breaker;
import entity.Candidates;
import entity.Cluster;
import entity.CombinationGenerator;
import entity.EventType;
import entity.Global;
import entity.MovingObject;
import entity.MyEvent;
import entity.TimeObject;
import entity.Velocity;

/**
 * 
 * @author xiaohui
 * 
 */
public class GroupDiscovery {
	// containers
	// map id to movingObjects
	static HashMap<Integer, MovingObject> allObjs = new HashMap<Integer, MovingObject>();
	// assume we also have a container for all objects
	static ArrayList<MovingObject> OBJ = new ArrayList<MovingObject>();
	static Candidates R;

	// map id to clusters
	static HashMap<Integer, Cluster> CS = new HashMap<Integer, Cluster>();
	static PriorityQueue<MyEvent> eventQ = new PriorityQueue<MyEvent>();

	// params
	// static int currTime = 0;
	static LocalTime nextReadTime = new LocalTime(Global.MINTIME);
	static LocalTime currTime = new LocalTime(Global.MINTIME);
	static LocalTime maxTime = new LocalTime(Global.MAXTIME);

	static Data dataSource = new Data();

	static int eps = 40;
	static int minPts = 3;
	static int tau = 180; // seconds
	static int k = 5;
	static double alpha, beta, gamma;
	static Trie aTrie;

	private static void ClusterObjects(ArrayList<Integer> U) {
		// convert integer into movingobjects
		MovingObject mo;
		ArrayList<MovingObject> objList = new ArrayList<MovingObject>();
		for (Integer i : U) {
			mo = allObjs.get(i);
			objList.add(mo);
		}
		DBScan.doDBScan(objList, eps, minPts, nextReadTime);

		// fill CS with new cluster
		Cluster tempC;
		ArrayList<Integer> newClusters = new ArrayList<Integer>();
		for (MovingObject tempMo : objList) {
			if (tempMo.cid > 0) {
				newClusters.add(tempMo.cid);
				tempC = CS.get(tempMo.cid);
				if (tempC == null) {
					tempC = new Cluster(tempMo.cid);
					CS.put(tempMo.cid, tempC);
				}
				tempC.add(tempMo.oid);

				if (!tempMo.label) {
					MyEvent event = new MyEvent(tempMo.exitTime, tempMo.oid,
							tempC.clusterId, EventType.EXIT);
					eventQ.add(event);
				}
			}
		}
		// get expire time for newly created clusters
		for (Integer i : newClusters) {
			tempC = CS.get(i);
			Breaker b = getExpireTime(tempC);
			tempC.expiryTime = b.time;
			eventQ.add(new MyEvent(b.time, b.moid, tempC.clusterId, EventType.EXPIRE));
		}
	}

	/**
	 * Fill up clusters hashmap; <br>
	 * fill up eventQ
	 */
	private static void ClusterObjects() {
		DBScan.doDBScan(OBJ, eps, minPts, nextReadTime);
		/**
		 * check clustering
		 * 
		 */
		// for (MovingObject mo : OBJ) {
		// System.out.println(mo.dataPoint.routeId + " "
		// + mo.dataPoint.toString() + " " + mo.cid + " " + mo.label);
		// }

		Cluster tempC;
		for (MovingObject mo : OBJ) {
			if (mo.cid > 0) {
				tempC = CS.get(mo.cid);
				if (tempC == null) {
					tempC = new Cluster(mo.cid);
					CS.put(mo.cid, tempC);
				}
				tempC.add(mo.oid);
			}
		}

		// add events to eventQ
		for (Integer i : CS.keySet()) {
			tempC = CS.get(i);
			for (Integer moid : tempC.members) {
				MovingObject mo = allObjs.get(moid);
				// insert exit event into eventQ for border objects
				if (!mo.label) {
					MyEvent event = new MyEvent(mo.exitTime, mo.oid,
							tempC.clusterId, EventType.EXIT);
					eventQ.add(event);
				}
				// insert expire event into eventQ
			}
			Breaker b = getExpireTime(tempC);
			tempC.expiryTime = b.time;
			eventQ.add(new MyEvent(b.time, b.moid, tempC.clusterId, EventType.EXPIRE));
		}

		// process noise objects
		for (MovingObject mo : OBJ) {
			if (mo.cid == -1) {
				// insert join event for noise objects
				// range search for core objects
				// find the soonest cluster to join
				double range = 5 * eps;
				ArrayList<MovingObject> nei = DBScan.rangeQuery(mo, OBJ, range);
				LocalTime minTime = null;
				Cluster c = null;
				nei.remove(mo);
				for (MovingObject tempMo : nei) {
					if (tempMo.label && tempMo.cid > 0) {
						LocalTime t = getEnterTime(eps, mo, tempMo,
								nextReadTime);
						if (minTime == null || minTime.isAfter(t)) {
							minTime = t;
							c = CS.get(tempMo.cid);
						}
					}
				}

				// insert the event into eventQ
				if (minTime != null) {
					eventQ.add(new MyEvent(minTime, mo.oid, c.clusterId,
							EventType.JOIN));
				}
			}
		}

		// process trie
		aTrie = new Trie();
		for (Integer i : CS.keySet()) {
			tempC = CS.get(i);
			for (int numEle = minPts; numEle <= tempC.members.size(); numEle++) {
				String[][] combinations = getCombination(tempC, numEle);
				aTrie.insert(tempC.members, combinations, nextReadTime);
			}
		}
		// System.out.println(aTrie.toString());
		// System.out.println(aTrie.getNumPaths());
		// System.out.println(aTrie.getLeafEntry(new String[] { "1004", "1049",
		// "1082" }));
	}

	static String[][] getCombination(Cluster aCluster, int r) {

		Collections.sort(aCluster.members);
		int memSize = aCluster.members.size();
		Integer[] elements = aCluster.members.toArray(new Integer[memSize]);

		CombinationGenerator combGen = new CombinationGenerator(
				aCluster.members.size(), r);

		String[][] resArr = new String[combGen.getTotal().intValue()][];
		int counter = 0;
		String[] combination;
		int[] indices;
		while (combGen.hasMore()) {
			combination = new String[r];
			indices = combGen.getNext();
			for (int i = 0; i < indices.length; i++) {
				combination[i] = elements[indices[i]] + "";
			}
			// System.out.println(combination.toString());
			resArr[counter++] = combination;
		}
		return resArr;
	}

	private static LocalTime getEnterTime(int range, MovingObject noiseObj,
			MovingObject coreObj, LocalTime refTime) {

		// compute distances
		double objDist = noiseObj.distance(coreObj) - range;

		// compute relative velocities
		double deltaX = noiseObj.getX() - coreObj.getX();
		double deltaY = noiseObj.getY() - coreObj.getY();
		double sinTheta = deltaX / Math.sqrt(deltaX * deltaX + deltaY * deltaY);
		double cosTheta = Math.sqrt(1 - sinTheta * sinTheta);

		double deltaVX = noiseObj.v.getVx() - coreObj.v.getVx();
		double deltaVY = noiseObj.v.getVy() - coreObj.v.getVy();
		double along = deltaVX * sinTheta + deltaVY * cosTheta;

		return refTime.plusSeconds((int) (objDist / along));
	}

	private static Breaker getExpireTime(Cluster aCluster) {
		Breaker breaker = new Breaker();

		// LocalTime resTime = null;
		boolean first = true;
		// MovingObject tempMO;
		ArrayList<MovingObject> objMembers = new ArrayList<MovingObject>(
				aCluster.members.size());
		for (Integer i : aCluster.members) {
			objMembers.add(allObjs.get(i));
		}
		for (MovingObject mo : objMembers) {
			if (mo.label) {
				// retrieve neighbors for the core object
				ArrayList<MovingObject> neighbors = DBScan.rangeQuery(mo, OBJ,
						eps);
				neighbors.remove(mo);
				TimeObject[] tos = new TimeObject[neighbors.size()];
				int counter = 0;
				// order them by exit time
				for (MovingObject neighbor : neighbors) {
					LocalTime time = DBScan.getExitTime(eps, neighbor, mo,
							currTime);
					tos[counter++] = new TimeObject(time, neighbor);
				}
				Arrays.sort(tos);
				// get the m-2's exit time as expiry time
				System.out.println(mo.oid + " time: " + tos[minPts - 2].time);
				if (first) {
					breaker.time = tos[minPts - 2].time;
					breaker.moid = mo.oid;
					first = false;
				} else {
					if (breaker.time.isAfter(tos[minPts - 2].time)) {
						breaker.time = tos[minPts - 2].time;
						breaker.moid = mo.oid;
					}
				}

			}

		}
		return breaker;
	}

	// private static void buildClusters(ArrayList<Integer> u) {
	// Cluster tempC;
	// MovingObject mo;
	// for (Integer i : u) {
	// mo = allObjs.get(i);
	// if (mo.cid > 0) {
	// tempC = CS.get(mo.cid);
	//
	// if (tempC == null) {
	// tempC = new Cluster(mo.cid);
	// CS.put(mo.cid, tempC);
	// }
	// tempC.add(mo.oid);
	// }
	// }
	// // fill in eventQ
	// for (int key : CS.keySet()) {
	// tempC = CS.get(key);
	//
	// // eventQ.add(new MyEvent(t, key));
	// }
	// }

	/**
	 * If cluster is a candidate, put into R; R updates its minScore if
	 * necessary
	 * 
	 * @param cluster
	 */
	private static void checkCandidate(LeafEntry entry) {
		entry.computeDuration();
		entry.setScore(alpha, beta, gamma, entry.getDuration());

		if (entry.ts.plusMinutes(tau).isBefore(entry.te)) {

			// true candidate
			if (R.candidates.size() < k) {
				R.candidates.add(entry);
				if (entry.getScore() < R.minScore) {
					R.minScore = entry.getScore();
				}
			} else if (entry.getScore() > R.minScore) {
				R.candidates.add(entry);
				R.updateMinScore();
			} else if (entry.getScore() == R.minScore) {
				R.candidates.add(entry);
			}
		}
	}

	/**
	 * 1. pinpoint the time where there are exactly m objects <br>
	 */
	private static LocalTime getStartTime(
			HashMap<Integer, ArrayList<DataPoint>> hm) {
		if (hm == null) {
			System.err.println("in getStartTime, hm is null");
			System.exit(0);
		}

		LocalTime resTime = null;
		DataPoint[] dpArray = new DataPoint[hm.size()];
		int counter = 0;
		for (Integer key : hm.keySet()) {
			dpArray[counter++] = hm.get(key).get(0);
		}
		Arrays.sort(dpArray);

		resTime = dpArray[minPts - 1].time;

		return resTime;
	}

	/**
	 * 
	 * fill up containers
	 * 
	 * @param hm
	 */
	private static void fillup(HashMap<Integer, ArrayList<DataPoint>> hm) {
		// i is routeid
		for (Integer i : hm.keySet()) {
			DataPoint dp = hm.get(i).get(0); // first data point
			// // oid = routeid
			MovingObject mo = new MovingObject(dp.routeId, dp, new Velocity(
					dp.vx, dp.vy));
			allObjs.put(mo.oid, mo);
			OBJ.add(mo);
		}
	}

	// time goes by gap variable
	// at every second if some events occur, process
	// otherwise go to next iteration
	public static void main(String[] args) throws Exception {
		// Cluster c1 = new Cluster(1);
		// c1.add(1);
		// c1.add(2);
		// c1.add(3);
		// c1.add(4);
		//
		// String[][] comm = getCombination(c1, 3);
		// for (String[] com : comm)
		// System.out.println(Arrays.toString(com));

		// String[] s1 = { "a", "b", "c" };
		// String[] s2 = { "a", "b", "c" };
		// System.out.println(Arrays.equals(s1, s2));
		doGroupDiscovery();
	}

	public static void doGroupDiscovery() throws Exception {
		// get a big chunk of data from database
		int gap = 20; // how long is a chunk (min)
		boolean readChunk = false;
		HashMap<Integer, ArrayList<DataPoint>> hm = null;

		while (nextReadTime.isBefore(maxTime)) {
			System.out.println(nextReadTime.toString());

			/**
			 * 1. Assume there are >=m objects at the beginning<br>
			 * 2. fill up containers <br>
			 * 3. update base time <br>
			 */
			if (nextReadTime.equals(new LocalTime(Global.MINTIME))) {
				// initial run
				System.out.println("system starts...");
				String nextTimeStr = Data.getNewTime(nextReadTime, gap, 0);
				String currTimeStr = Data.converTimeToString(nextReadTime);
				hm = dataSource.getDefinedTrajectories(Global.testTable,
						currTimeStr, nextTimeStr, 0);
				// start of the tracing
				fillup(hm);
				System.out.println("hm size: " + hm.size());
				for (Integer i : hm.keySet()) {
					ArrayList<DataPoint> pt = hm.get(i);
					System.out.println(pt);
				}

				// 1. doDBScan
				// 2. fill up CS
				ClusterObjects();
				// System.out.println("CS size:" + CS.size());
				nextReadTime = nextReadTime.plusMinutes(gap);
			} else if (readChunk) {
				// read from database
				String nextTimeStr = Data.getNewTime(nextReadTime, gap, 0);
				String currTimeStr = Data.converTimeToString(nextReadTime);
				hm = dataSource.getDefinedTrajectories(Global.testTable,
						currTimeStr, nextTimeStr, 0);
				nextReadTime = nextReadTime.plusMinutes(gap);
				readChunk = false;
			} else {
				// process these data
				while (!eventQ.isEmpty()
						&& eventQ.peek().time.isBefore(nextReadTime)) {
					currTime = eventQ.peek().time;
					// move time to event time
					for (MovingObject mo : OBJ) {
						// get expected position
						DataPoint dp = dataSource.getExpectedDataPoint(mo.oid,
								currTime);
						mo.setDataPoint(dp);
					}

					MyEvent evt = eventQ.poll();
					Cluster cluster = CS.get(evt.CID);
					MovingObject mo = allObjs.get(evt.OID);
					ArrayList<Integer> U = new ArrayList<Integer>();
					// copy noise and unclassified data into U
					for (MovingObject tempMo : OBJ) {
						if (tempMo.cid <= 0) {
							U.add(tempMo.oid);
						}
					}

					// double duration = currTime - cluster.startTime;
					// cluster.updateScore(alpha, beta, gamma, duration);

					// check if cluster is a true candidate;

					// order of insert/deletion and updateTrie is important
					if (evt.type == EventType.EXIT) {
						updateTrie(evt);
						cluster.delete(mo.oid);
						mo.cid = 0;
						U.add(mo.oid);
						Insert(U);
					} else if (evt.type == EventType.JOIN) {
						cluster.add(mo.oid);
						updateTrie(evt);

						mo.cid = cluster.clusterId;
						U.remove(mo);
						LocalTime t = getExitTime(mo, cluster);
						MyEvent event = new MyEvent(t, mo.oid,
								cluster.clusterId, EventType.EXIT);
						eventQ.add(event);
					} else if (evt.type == EventType.EXPIRE) {
						// check if the current cluster really needs to be
						// rebuilt
						ArrayList<MovingObject> L = DBScan.rangeQuery(mo, OBJ,
								eps);
						if (L.size() >= minPts) {
							// re-compute the expire time
							Breaker b = getExpireTime(cluster);
							cluster.expiryTime = b.time;
							eventQ.add(new MyEvent(b.time, b.moid, cluster.clusterId,
									EventType.EXPIRE));
						} else {
							// get combinations for old cluster
							// for each member, get its new member cluster
							// intersect to detect those that are still
							// traveling
							// tgr

							String[][] com1 = getCombination(cluster, minPts);
							ArrayList<String[]> toBeDel = new ArrayList<String[]>(
									com1.length * 2);
							for (String[] sArr : com1) {
								toBeDel.add(sArr);
							}
							for (int numEle = minPts + 1; numEle <= cluster.members
									.size(); numEle++) {
								String[][] combinations = getCombination(
										cluster, numEle);
								for (String[] sArr : combinations) {
									toBeDel.add(sArr);
								}
							}
							System.out
									.println("tobedel size;" + toBeDel.size());

							ArrayList<Integer> oldMems = new ArrayList<Integer>(
									cluster.members);
							Collections.copy(oldMems, cluster.members);

							MovingObject tempMo;
							for (int i : cluster.members) {
								// remove cluster id for each object
								tempMo = allObjs.get(i);
								tempMo.cid = 0;
								tempMo.label = false;
								U.add(tempMo.oid);
							}
							// remove from CS
							CS.remove(cluster.clusterId);
							System.out.println("after expire, cs size"
									+ CS.size());
							System.out.println("U size:" + U.size());
							Insert(U);

							for (Integer i : oldMems) {
								if (allObjs.get(i).cid > 0) {
									ArrayList<Integer> newMem = CS.get(allObjs
											.get(i).cid).members;
									Collection<Integer> common = CollectionUtils
											.intersection(oldMems, newMem);
									String[][] sCommon = getCombination(common,
											minPts);
									for (String[] sArr : sCommon) {
										int index = getIndex(sArr, toBeDel);
										if (index != -1) {
											toBeDel.set(index, null);
										}
									}
								}
							}

							// remove entry of toBeDeleted from trie
							for (String[] sArr : toBeDel) {
								if (sArr != null) {
									LeafEntry le = aTrie.remove(sArr,
											nextReadTime);
									checkCandidate(le);
								}
							}
						} // end if
					} // end else if
				}// end while
				if (eventQ.peek().time.isBefore(nextReadTime)) {
					readChunk = true;
				}
			} // end else

		}// end while

		// close database connection
		dataSource.closeConnection();
	}

	/**
	 * 
	 * @param sArr
	 * @param toBeDel
	 * @return index of sArr in toBeDel; -1 if not found
	 */
	private static int getIndex(String[] sArr, ArrayList<String[]> toBeDel) {
		int i = 0;
		for (String[] s : toBeDel) {
			if (Arrays.equals(s, sArr)) {
				return i;
			}
			i++;
		}
		return -1;
	}

	/**
	 * 
	 * @param collection
	 * @param r
	 * @return the combination of length r in collection
	 */
	private static String[][] getCombination(Collection<Integer> collection,
			int r) {
		List<Integer> list = new ArrayList<Integer>(collection);
		Collections.sort(list);

		int memSize = list.size();
		Integer[] elements = list.toArray(new Integer[memSize]);

		CombinationGenerator combGen = new CombinationGenerator(memSize, r);

		String[][] resArr = new String[combGen.getTotal().intValue()][];
		int counter = 0;
		String[] combination;
		int[] indices;
		while (combGen.hasMore()) {
			combination = new String[r];
			indices = combGen.getNext();
			for (int i = 0; i < indices.length; i++) {
				combination[i] = elements[indices[i]] + "";
			}
			// System.out.println(combination.toString());
			resArr[counter++] = combination;
		}
		return resArr;
	}

	private static void updateTrie(MyEvent evt) throws Exception {
		Cluster cluster = CS.get(evt.CID);
		if (evt.type == EventType.EXIT) {
			// assuming object is not deleted yet
			String[][] strArr = getCombination(cluster, minPts);
			for (String[] str : strArr) {
				if (Arrays.asList(str).contains(evt.OID + "")) {
					LeafEntry entry = aTrie.remove(str, evt.OID + "",
							nextReadTime);
					checkCandidate(entry);
				}
			}
		} else if (evt.type == EventType.JOIN) {
			// assuming object is already inserted
			String[][] strArr = getCombination(cluster, minPts);
			for (String[] str : strArr) {
				if (Arrays.asList(str).contains(evt.OID + "")) {
					aTrie.insert(str, nextReadTime);
				}
				// LeafEntry entry = aTrie.remove(str, evt.OID+"", currTime);
				// checkCandidate(entry);
				// }
			}
		}
	}

	/**
	 * 
	 * @param mo
	 * @param cluster
	 * @return the core object that longest together time
	 */
	private static LocalTime getExitTime(MovingObject mo, Cluster cluster) {
		MovingObject tempMo;
		LocalTime minTime = null;
		for (Integer i : cluster.members) {
			tempMo = OBJ.get(i);
			if (tempMo.label && tempMo.distance(mo) <= eps) {
				LocalTime t = DBScan.getExitTime(eps, mo, tempMo, nextReadTime);
				if (minTime == null || minTime.isBefore(t)) {
					minTime = t;
				}
			}
		}
		return minTime;
	}

	/**
	 * insert with real data points
	 * 
	 * @param u2
	 * @param oBJ2
	 * @param eps2
	 * @param minPts2
	 * @param tau2
	 * @param r2
	 */
	private static void Insert(ArrayList<Integer> U) {
		ArrayList<Integer> G = new ArrayList<Integer>();
		MovingObject mo;
		for (Integer i : U) {
			mo = allObjs.get(i);
			ArrayList<MovingObject> L = DBScan.rangeQuery(mo, OBJ, eps);
			L.remove(mo);
			for (MovingObject tempMo : L) {
				if (tempMo.cid > 0) {
					G.add(tempMo.oid);
				}
			}
		}

		System.out.println("G size: " + G.size());
		for (Integer i : G) {
			mo = allObjs.get(i);
			Cluster c = CS.get(mo.cid);
			ArrayList<MovingObject> L = DBScan.rangeQuery(mo, OBJ, eps);
			if (L.size() >= minPts) {
				L.remove(mo);
				mo.label = true;
				for (MovingObject tempMo : L) {
					if (tempMo.cid <= 0) {
						c.add(tempMo.oid);
						tempMo.cid = c.clusterId;
						trieObjInsert(c, tempMo);
						U.remove(new Integer(tempMo.oid));
						DBScan.expandCluster(OBJ, tempMo, c.clusterId, eps,
								minPts, nextReadTime);
					} else if (tempMo.cid != mo.cid && tempMo.label) {
						Cluster c1 = CS.get(tempMo.cid);
						// insert members of c1 into c
						merge(c, c1);
						trieMergeCluster(c);
					}
				}
				Breaker b= getExpireTime(c);
				MyEvent e = new MyEvent(b.time, b.moid, c.clusterId, EventType.EXPIRE);
				eventQ.add(e);
			}
		}
		if (U.size() >= minPts) {
			ClusterObjects(U);
		}
	}

	private static void trieMergeCluster(Cluster cluster) {
		// form combination
		// if there is no path, create new path for string
		// assuming clusters are already merged
		String[][] strArr = getCombination(cluster, minPts);
		for (String[] str : strArr) {
			if (aTrie.getLeafEntry(str) == null) {
				aTrie.insert(cluster.members, strArr, nextReadTime);
			}
		}
	}

	private static void trieObjInsert(Cluster cluster, MovingObject mo) {
		// assuming object is already inserted
		String[][] strArr = getCombination(cluster, minPts);
		for (String[] str : strArr) {
			if (Arrays.asList(str).contains(mo.oid + "")) {
				aTrie.insert(str, nextReadTime);
			}
			// LeafEntry entry = aTrie.remove(str, evt.OID+"", currTime);
			// checkCandidate(entry);
			// }
		}
	}

	/**
	 * add cluster c1 into c
	 * 
	 * @param c
	 * @param c1
	 * @return
	 */
	private static void merge(Cluster c, Cluster c1) {
		for (Integer i : c1.members) {
			c.add(i);
			OBJ.get(i).cid = c.clusterId;
			c1.delete(i);
		}
	}

	// /**
	// *
	// * @param cid
	// * @param cid2
	// */
	// static void merge(int cid1, int cid2) {
	// Cluster c1 = CS.get(cid1);
	// Cluster c2 = CS.get(cid2);
	// if (c1.duration >= tau) {
	// checkCandidate(c1);
	// }
	// if (c2.duration >= tau) {
	// checkCandidate(c2);
	// }
	// Cluster newCluster = new Cluster(Global.nextCid());
	// for (Integer i : c1.members) {
	// newCluster.add(i);
	// allObjs.get(i).cid = newCluster.clusterId;
	// }
	// for (Integer i : c2.members) {
	// newCluster.add(i);
	// allObjs.get(i).cid = newCluster.clusterId;
	// }
	//
	// // double t = newCluster.getExpireTime();
	// // eventQ.add(new MyEvent(t, newCluster.clusterId));
	//
	// }

}