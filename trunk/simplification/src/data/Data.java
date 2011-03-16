/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.LocalDateTime;
import org.joda.time.LocalTime;
import org.joda.time.Seconds;

import simplification.Database;

import com.vividsolutions.jts.geom.Coordinate;

/**
 * 
 * @author ceikute, xiaohui
 */
public class Data {

	public static String converTimeToString(LocalTime lt) {
		String ret = lt.toString();
		return ret.substring(0, ret.indexOf("."));
	}

	/**
	 * method to retrieve defines trajectories from provided DB table and time
	 * range type = {1, 0} : 1 - considering date and time, 0 - considering only
	 * time <br>
	 * 
	 * time format: "00:00:00" or "2001-01-01 00:00:00"<br>
	 * 
	 * 
	 * @param tbName
	 *            : table name
	 * @param timeFrom
	 * @param timeTo
	 * @param type
	 *            0
	 * @return hashmap : route id -> arraylist of datapoints within provided
	 *         time period
	 * @throws Exception
	 */
	public static HashMap<Integer, ArrayList<DataPoint>> getDefinedTrajectories(
			String tbName, String timeFrom, String timeTo, int type)
			throws Exception {

		Database db = new Database();
		String select = "";

		if (type == 1) {
			select = "select * from simpl_get_defined_routes('"
					+ tbName
					+ "', '"
					+ timeFrom
					+ "', '"
					+ timeTo
					+ "') as p(routeid int8, mpx text, mpy text, time time, stamp timestamp, time0 bigint)";
		} else if (type == 0) {
			select = "select * from simpl_get_defined_routes_t('"
					+ tbName
					+ "', '"
					+ timeFrom
					+ "', '"
					+ timeTo
					+ "') as p(routeid int8, mpx text, mpy text, time time, stamp timestamp, time0 bigint)";
		}
		PreparedStatement ps = null;
		ResultSet result = null;

		ps = db.getConnection().prepareStatement(select);
		result = ps.executeQuery();

		HashMap<Integer, ArrayList<DataPoint>> hm = new HashMap<Integer, ArrayList<DataPoint>>();

		ArrayList<DataPoint> points = null;

		int routeId = -1;

		while (result.next()) {
			int routeNext = result.getInt("routeid");
			if (routeId == -1) {
				routeId = routeNext;
				points = new ArrayList<DataPoint>();

			} else if (routeId != routeNext) {
				hm.put(routeId, points);

				routeId = routeNext;
				points = new ArrayList<DataPoint>();
			}
			Coordinate c = new Coordinate(Double.parseDouble(result
					.getString("mpx")), Double.parseDouble(result
					.getString("mpy")));
			LocalTime aTime = new LocalTime(result.getString("time"));
			LocalDateTime dateTime = new LocalDateTime(result
					.getString("stamp").replace(" ", "T"));
			double vx = result.getFloat("vx");
			double vy = result.getFloat("vy");

			DataPoint dp = new DataPoint(routeId, c, vx, vy, aTime, dateTime,
					result.getInt("time0"));
			points.add(dp);
		}
		hm.put(routeId, points);

		ps.close();
		result.close();
		db.closeConnection();

		return hm;
	}

	/**
	 * 
	 * @param t1_p1
	 *            - trajectory point 1
	 * @param t1_p2
	 *            - trajectory point 2
	 * @param p
	 *            - sample point
	 * @param type
	 *            - 1 or 0
	 * @return imaginary data point between provided trajectory 1 points, at
	 *         time from p
	 */
	public static DataPoint getImaginaryPoint(DataPoint t1_p1, DataPoint t1_p2,
			DataPoint p, int type) {
		Seconds sec = null;
		int secBetween = -1;

		if (type == 1) {
			// DateTime startStamp = new DateTime(t1_p1.dateTime.replace(" ",
			// "T"));
			// DateTime currStamp = new DateTime(p.dateTime.replace(" ", "T"));

			LocalDateTime startStamp = t1_p1.dateTime;
			LocalDateTime currStamp = p.dateTime;
			sec = Seconds.secondsBetween(startStamp, currStamp);
			secBetween = sec.getSeconds();
		} else {
			// LocalTime startStamp = new LocalTime(t1_p1.time);
			// LocalTime currStamp = new LocalTime(p.time);

			LocalTime startStamp = t1_p1.time;
			LocalTime currStamp = p.time;
			sec = Seconds.secondsBetween(startStamp, currStamp);
			secBetween = sec.getSeconds();

			if (secBetween < 0) {
				// String[] date = t1_p1.dateTime.split(" ");
				// LocalDate ld = new LocalDate(date[0]);
				LocalDate ld = t1_p1.dateTime.toLocalDate();
				ld = ld.plusDays(1);
				String dateStr = ld.getYear() + "-"
						+ fixDateOrTime(ld.getMonthOfYear()) + "-"
						+ fixDateOrTime(ld.getDayOfMonth());
				LocalDateTime midnight = new LocalDateTime(dateStr
						+ "T00:00:00");
				// DateTime p1 = new DateTime(t1_p1.dateTime.replace(" ", "T"));
				LocalDateTime p1 = t1_p1.dateTime;

				sec = Seconds.secondsBetween(p1, midnight);
				secBetween = sec.getSeconds();
				// DateTime p2 = new DateTime(p.dateTime.replace(" ", "T"));

				LocalDateTime p2 = p.dateTime;
				sec = Seconds.secondsBetween(midnight, p2);
				secBetween = secBetween + sec.getSeconds();
			}
		}

		int time = t1_p1.time0 + secBetween;

		double tau = (time - t1_p1.time0) / (t1_p2.time0 - t1_p1.time0);

		double x = tau * (t1_p2.p.x - t1_p1.p.x) + t1_p1.p.x;
		double y = tau * (t1_p2.p.y - t1_p1.p.y) + t1_p1.p.y;

		DataPoint newP = new DataPoint(t1_p1.routeId, new Coordinate(x, y),
				t1_p1.vx, t1_p1.vy, p.time, p.dateTime, time);

		return newP;
	}

	// public ArrayList<DataPoint> getNextDataPoint() {
	//
	// }

	private static String fixDateOrTime(int time) {
		String t_str = Integer.toString(time);
		if (t_str.length() < 2)
			t_str = "0" + t_str;
		return t_str;
	}

	/**
	 * 
	 * @param time
	 *            - time from
	 * @param hours
	 *            - number of hours to add
	 * @param type
	 *            - 1 - date and time; 0 - time
	 * @return new string of next time
	 */
	public static String getNewTime(String time, int hours, int type) {
		String newTime = "";

		if (type == 1) {
			DateTime dt = new DateTime(time.replace(" ", "T"));
			dt = dt.plusHours(hours);

			newTime = dt.getYear() + "-" + fixDateOrTime(dt.getMonthOfYear())
					+ "-" + fixDateOrTime(dt.getDayOfMonth()) + " "
					+ fixDateOrTime(dt.getHourOfDay()) + ":"
					+ fixDateOrTime(dt.getMinuteOfHour()) + ":"
					+ fixDateOrTime(dt.getSecondOfMinute());
		} else if (type == 0) {
			LocalTime lt = new LocalTime(time);
			lt = lt.plusHours(hours);

			newTime = fixDateOrTime(lt.getHourOfDay()) + ":"
					+ fixDateOrTime(lt.getMinuteOfHour()) + ":"
					+ fixDateOrTime(lt.getSecondOfMinute());
		}

		return newTime;
	}

	/**
	 * 
	 * @param time
	 * @param hours
	 * @param type
	 * @return new string of next time
	 */
	public static String getNewTime(LocalTime time, int hours, int type) {
		String timestring = time.toString(); // toString returns "00:00:00.000"
		timestring = timestring.substring(0, timestring.indexOf("."));
		return getNewTime(timestring, hours, type);
	}
}
