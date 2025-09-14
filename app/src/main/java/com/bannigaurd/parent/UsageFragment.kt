package com.bannigaurd.parent

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.concurrent.TimeUnit

class UsageFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var noUsageTextView: TextView
    private lateinit var pieChart: PieChart
    private lateinit var totalUsageTextView: TextView

    private var childDeviceId: String? = null
    private val auth = Firebase.auth
    private val firestore = Firebase.firestore
    private val rtdb = Firebase.database.reference

    private var usageListener: ValueEventListener? = null
    private lateinit var usageRef: DatabaseReference
    private lateinit var commandRef: DatabaseReference

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_usage, container, false)
        recyclerView = view.findViewById(R.id.rvAppUsage)
        progressBar = view.findViewById(R.id.progressBar)
        noUsageTextView = view.findViewById(R.id.tvNoUsage)
        pieChart = view.findViewById(R.id.pieChart)
        totalUsageTextView = view.findViewById(R.id.tvTotalUsage)
        recyclerView.layoutManager = LinearLayoutManager(context)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupPieChart()
        fetchLinkedDevice()
    }

    private fun fetchLinkedDevice() {
        progressBar.visibility = View.VISIBLE
        val parentUid = auth.currentUser?.uid ?: return

        firestore.collection("users").document(parentUid).get().addOnSuccessListener { doc ->
            if (!isAdded) return@addOnSuccessListener
            val devices = doc.get("linkedDevices") as? List<String>
            childDeviceId = (activity?.application as? MyApp)?.childDeviceId ?: devices?.firstOrNull()

            if (childDeviceId != null) {
                commandRef = rtdb.child("commands").child(childDeviceId!!)
                fetchUsageData()
            } else {
                handleError("No active device found.")
            }
        }
    }

    private fun fetchUsageData() {
        usageRef = rtdb.child("appUsage").child(childDeviceId!!)
        usageListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                progressBar.visibility = View.GONE

                val usageList = mutableListOf<AppUsageInfo>()
                snapshot.children.forEach {
                    it.getValue(AppUsageInfo::class.java)?.let { usage ->
                        usageList.add(usage)
                    }
                }

                if (usageList.isEmpty()) {
                    handleError("No usage data for today yet.")
                } else {
                    noUsageTextView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE

                    usageList.sortWith(compareByDescending<AppUsageInfo> { it.isForeground }
                        .thenByDescending { it.usageTimeToday })

                    val adapter = UsageAdapter(requireContext(), usageList) { app, isBlocked ->
                        toggleAppBlock(app, isBlocked)
                    }
                    recyclerView.adapter = adapter

                    val usedApps = usageList.filter { it.usageTimeToday > 0 }
                    updateChartAndTotal(usedApps)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if(isAdded) handleError("Failed to load usage data.")
            }
        }
        usageRef.addValueEventListener(usageListener!!)
    }

    private fun toggleAppBlock(app: AppUsageInfo, isBlocked: Boolean) {
        if (childDeviceId == null || app.packageName == null) {
            Toast.makeText(context, "Cannot process request.", Toast.LENGTH_SHORT).show()
            return
        }

        // --- बदलाव यहाँ किया गया है ---
        // पैकेज नाम को Firebase के लिए एक valid key बनाने के लिए बदला गया है
        val safePackageName = app.packageName.replace('.', '_')

        val appControlRef = commandRef.child("appControls").child(safePackageName)
        appControlRef.child("isBlocked").setValue(isBlocked)
            .addOnSuccessListener {
                val status = if (isBlocked) "blocked" else "unblocked"
                Toast.makeText(context, "${app.appName} ${status}.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Failed to update status.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupPieChart() {
        pieChart.isDrawHoleEnabled = true
        pieChart.setHoleColor(Color.TRANSPARENT)
        pieChart.setTransparentCircleColor(Color.TRANSPARENT)
        pieChart.setUsePercentValues(true)
        pieChart.description.isEnabled = false
        pieChart.legend.isEnabled = false
        pieChart.setDrawEntryLabels(false)
    }

    private fun updateChartAndTotal(apps: List<AppUsageInfo>) {
        if (apps.isEmpty()) {
            pieChart.visibility = View.INVISIBLE
            totalUsageTextView.text = "0h 0m"
            return
        }
        pieChart.visibility = View.VISIBLE
        val totalMillis = apps.sumOf { it.usageTimeToday }
        totalUsageTextView.text = formatDuration(totalMillis)

        val topApps = apps.take(5)
        val otherUsage = apps.drop(5).sumOf { it.usageTimeToday }

        val entries = ArrayList<PieEntry>()
        topApps.forEach {
            entries.add(PieEntry(it.usageTimeToday.toFloat(), it.appName))
        }
        if (otherUsage > 0) {
            entries.add(PieEntry(otherUsage.toFloat(), "Others"))
        }

        val dataSet = PieDataSet(entries, "App Usage")
        dataSet.colors = CHART_COLORS
        dataSet.sliceSpace = 2f
        dataSet.valueTextColor = Color.WHITE
        dataSet.valueTextSize = 12f

        val data = PieData(dataSet)
        data.setValueFormatter(PercentFormatter(pieChart))
        pieChart.data = data
        pieChart.animateY(1000)
        pieChart.invalidate()
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return String.format("%dh %dm", hours, minutes)
    }

    private fun handleError(message: String) {
        if (isAdded) {
            progressBar.visibility = View.GONE
            recyclerView.visibility = View.GONE
            noUsageTextView.text = message
            noUsageTextView.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::usageRef.isInitialized && usageListener != null) {
            usageRef.removeEventListener(usageListener!!)
        }
    }

    companion object {
        val CHART_COLORS = listOf(
            Color.parseColor("#3B82F6"), // Blue
            Color.parseColor("#10B981"), // Green
            Color.parseColor("#F59E0B"), // Amber
            Color.parseColor("#EF4444"), // Red
            Color.parseColor("#8B5CF6"), // Violet
            Color.parseColor("#6B7280")  // Gray for "Others"
        )
    }
}