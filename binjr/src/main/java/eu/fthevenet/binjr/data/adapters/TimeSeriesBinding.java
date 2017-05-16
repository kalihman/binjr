package eu.fthevenet.binjr.data.adapters;

import eu.fthevenet.binjr.data.workspace.ChartType;
import eu.fthevenet.binjr.data.workspace.UnitPrefixes;
import eu.fthevenet.binjr.sources.jrds.adapters.JrdsSeriesBindingFactory;
import javafx.scene.paint.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Represents a binding between a time series and the {@link DataAdapter} used to produce it.
 *
 * @author Frederic Thevenet
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "Binding")
public class TimeSeriesBinding<T extends Number> {
    private static final Logger logger = LogManager.getLogger(JrdsSeriesBindingFactory.class);
    private static final ThreadLocal<MessageDigest> messageDigest = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            logger.fatal("Failed to instantiate MD5 message digest");
            throw new IllegalStateException("Failed to create a new instance of Md5HashTargetResolver", e);
        }
    });
    private final static Color[] defaultChartColors = new Color[]{
            Color.LIGHTBLUE,
            Color.LIGHTCORAL,
            Color.LIGHTCYAN,
            Color.LIGHTGRAY,
            Color.LIGHTGREEN,
            Color.LEMONCHIFFON,
            Color.LAVENDER,
            Color.LIGHTPINK,
            Color.LIGHTSALMON,
            Color.LIGHTSEAGREEN,
            Color.LIGHTSKYBLUE,
            Color.LIGHTSLATEGRAY,
            Color.LIGHTSTEELBLUE,
            Color.LIGHTYELLOW,
            Color.MEDIUMBLUE,
            Color.MEDIUMORCHID,
            Color.MEDIUMPURPLE,
            Color.MEDIUMSEAGREEN,
            Color.MEDIUMSPRINGGREEN,
            Color.MEDIUMTURQUOISE,
            Color.MEDIUMVIOLETRED,
            Color.MINTCREAM,
    };

    private final UUID adapterId;
    private final String label;
    private final String path;
    private final Color color;
    private final String legend;
    private final UnitPrefixes prefix;
    private final ChartType graphType;
    private final String unitName;
    private final String treeHierarchy;

    @XmlTransient
    private DataAdapter<T> adapter;


    public TimeSeriesBinding() {
        this.label = "";
        this.path = "";
        this.color = null;
        this.legend = "";
        this.prefix = UnitPrefixes.BINARY;
        this.graphType = ChartType.STACKED;
        this.unitName = "";
        this.adapter = null;
        this.treeHierarchy = "";
        adapterId = null;
    }

    public TimeSeriesBinding(String label, String path, Color color, String legend, UnitPrefixes prefix, ChartType graphType, String unitName, String treeHierarchy, DataAdapter<T> adapter) {
        this.label = label;
        this.path = path;
        this.legend = legend;
        this.prefix = prefix;
        this.graphType = graphType;
        this.unitName = unitName;
        this.treeHierarchy = treeHierarchy;
        this.adapter = adapter;
        this.adapterId = adapter != null ? adapter.getId() : null;
        if (color == null) {
            // pickup a default color at random, based on the hash of the binding path, so it stays stable
            this.color = computeDefaultColor();
        }
        else {
            this.color = color;
        }
    }

    /**
     * Returns the label of the binding
     *
     * @return the label of the binding
     */
    public String getLabel() {
        return this.label;
    }

    /**
     * Returns the path of the binding
     *
     * @return the path of the binding
     */
    public String getPath() {
        return this.path;
    }

    /**
     * Returns the {@link DataAdapter} of the binding
     *
     * @return the {@link DataAdapter} of the binding
     */
    @XmlTransient
    public DataAdapter<T> getAdapter() {
        return this.adapter;
    }

    public void setAdapter(DataAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Returns the color of the bound series as defined in the source.
     *
     * @return the color of the bound series as defined in the source.
     */
    public Color getColor() {
        return this.color;
    }

    /**
     * Returns the legend of the bound series as defined in the source.
     *
     * @return the legend of the bound series as defined in the source.
     */
    public String getLegend() {
        return this.legend;
    }

    /**
     * Returns the type of graph of the bound series as defined in the source.
     *
     * @return the type of graph of the bound series as defined in the source.
     */
    public ChartType getGraphType() {
        return graphType;
    }

    /**
     * Returns the unit name of the bound series as defined in the source.
     *
     * @return the  unit name of the bound series as defined in the source.
     */
    public String getUnitName() {
        return unitName;
    }

    /**
     * Returns the unit numerical base of the bound series as defined in the source.
     *
     * @return the  unit numerical base of the bound series as defined in the source.
     */
    public UnitPrefixes getUnitPrefix() {
        return prefix;
    }

    /**
     * Gets the {@link DataAdapter}'s id
     *
     * @return the {@link DataAdapter}'s id
     */
    public UUID getAdapterId() {
        return adapterId;
    }

    @Override
    public String toString() {
        return getLegend();
    }

    public String getTreeHierarchy() {
        return treeHierarchy;
    }

    private Color computeDefaultColor() {
        long targetNum = getHashValue(this.getTreeHierarchy()) % defaultChartColors.length;
        if (targetNum < 0) {
            targetNum = targetNum * -1;
        }
        return defaultChartColors[((int) targetNum)];
    }

    private long getHashValue(final String value) {
        long hashVal;
        messageDigest.get().update(value.getBytes(Charset.forName("UTF-8")));
        hashVal = new BigInteger(1, messageDigest.get().digest()).longValue();
        if (logger.isDebugEnabled()) {
            logger.debug("Hash: [" + hashVal + "] original value: [" + value + "]");
        }
        return hashVal;
    }

}
