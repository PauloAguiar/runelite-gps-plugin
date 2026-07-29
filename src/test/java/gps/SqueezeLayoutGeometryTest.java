package gps;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Headless mirror of the panel's issue-#13 layout (SqueezeLayout + elastic catalog + outer top
 * scroll): fixed chrome, an elastic rows box, a results slot. Asserts the three regimes — tall
 * window (rows box fills, no dead space), short window (rows box floors, outer scroll engages
 * with a visible scrollbar), and mid window — using real Swing layout, no display needed.
 */
public class SqueezeLayoutGeometryTest
{
	private static final int CHROME_TOP = 120;
	private static final int CHROME_BOTTOM = 70;
	private static final int ROWS_CONTENT = 400;
	private static final int ROWS_MIN = 60;
	private static final int RESULTS_MIN = 150;
	private static final int WIDTH = 225;

	private JPanel fixed(int height)
	{
		JPanel panel = new JPanel();
		panel.setPreferredSize(new Dimension(WIDTH, height));
		panel.setMinimumSize(new Dimension(0, height));
		return panel;
	}

	/** Recreates the panel wiring with plain components; returns [root, topScroll, rowsScroll, results]. */
	private Object[] build()
	{
		JPanel rows = new JPanel();
		rows.setPreferredSize(new Dimension(WIDTH, ROWS_CONTENT));
		JPanel[] topRef = new JPanel[2];
		JPanel[] rootRef = new JPanel[1];
		// Pull model, mirroring FlexRowsScroll: preferred height computed on demand, guard
		// breaking the recursion through the chrome measurement.
		JScrollPane rowsScroll = new JScrollPane(rows,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
		{
			private boolean measuring;

			@Override
			public Dimension getPreferredSize()
			{
				if (measuring)
				{
					return new Dimension(10, 0);
				}
				measuring = true;
				try
				{
					int chrome = topRef[0].getPreferredSize().height;
					int reserve = Math.min(RESULTS_MIN, topRef[1].getPreferredSize().height);
					int budget = rootRef[0].getHeight() - reserve;
					return new Dimension(10,
						Math.max(Math.min(ROWS_CONTENT + 2, budget - chrome), ROWS_MIN));
				}
				finally
				{
					measuring = false;
				}
			}
		};
		rowsScroll.setBorder(BorderFactory.createEmptyBorder());

		JPanel top = new JPanel(new BorderLayout());
		top.add(fixed(CHROME_TOP), BorderLayout.NORTH);
		JPanel mid = new JPanel(new BorderLayout());
		mid.add(rowsScroll, BorderLayout.CENTER);
		top.add(mid, BorderLayout.CENTER);
		top.add(fixed(CHROME_BOTTOM), BorderLayout.SOUTH);

		JPanel topWrapper = new JPanel(new BorderLayout());
		topWrapper.add(top, BorderLayout.NORTH);
		JScrollPane topScroll = new JScrollPane(topWrapper,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		topScroll.setBorder(BorderFactory.createEmptyBorder());

		JPanel results = new JPanel();
		results.setPreferredSize(new Dimension(WIDTH, 40));
		topRef[0] = top;
		topRef[1] = results;

		JPanel root = new JPanel();
		rootRef[0] = root;
		root.setLayout(new java.awt.LayoutManager()
		{
			@Override
			public void layoutContainer(java.awt.Container parent)
			{
				int height = parent.getHeight();
				int width = parent.getWidth();
				int reserve = Math.min(RESULTS_MIN, results.getPreferredSize().height);
				int budget = Math.max(0, height - reserve);
				int topHeight = Math.min(topScroll.getPreferredSize().height, budget);
				topScroll.setVerticalScrollBarPolicy(
					topScroll.getPreferredSize().height > topHeight
						? ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
						: ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
				topScroll.setBounds(0, 0, width, topHeight);
				results.setBounds(0, topHeight, width, height - topHeight);
			}

			@Override
			public Dimension preferredLayoutSize(java.awt.Container parent)
			{
				return new Dimension(WIDTH, 0);
			}

			@Override
			public Dimension minimumLayoutSize(java.awt.Container parent)
			{
				return new Dimension(0, 0);
			}

			@Override
			public void addLayoutComponent(String name, java.awt.Component component)
			{
			}

			@Override
			public void removeLayoutComponent(java.awt.Component component)
			{
			}
		});
		root.add(topScroll);
		root.add(results);
		return new Object[]{root, topScroll, rowsScroll, results};
	}

	private void layout(Object[] parts, int height)
	{
		JPanel root = (JPanel) parts[0];
		root.setSize(WIDTH, height);
		// Two full passes with a deep invalidate between them: validate() no-ops on a valid
		// tree, which would freeze first-pass scrollbar state — the real client keeps
		// re-laying on the EDT, so the harness must too.
		root.validate();
		for (java.awt.Component c : new java.awt.Component[]{root, (java.awt.Component) parts[1]})
		{
			c.invalidate();
		}
		root.validate();
	}

	@Test
	public void tallWindowFillsWithoutDeadSpace()
	{
		Object[] parts = build();
		JPanel root = (JPanel) parts[0];
		JScrollPane topScroll = (JScrollPane) parts[1];
		JScrollPane rowsScroll = (JScrollPane) parts[2];
		layout(parts, 700);
		// budget 660; chrome 190 -> rows should get min(402, 470) = 402: natural content size,
		// and the top block ends exactly at chrome + rows with results below — no dead band.
		assertEquals(402, rowsScroll.getPreferredSize().height);
		assertEquals(CHROME_TOP + CHROME_BOTTOM + 402, topScroll.getHeight());
	}

	@Test
	public void midWindowRowsAbsorbTheShortage()
	{
		Object[] parts = build();
		JPanel root = (JPanel) parts[0];
		JScrollPane topScroll = (JScrollPane) parts[1];
		JScrollPane rowsScroll = (JScrollPane) parts[2];
		layout(parts, 500);
		// budget 460; rows = 460 - 190 = 270; top fits the budget exactly: no outer scrolling.
		assertEquals(270, rowsScroll.getPreferredSize().height);
		assertEquals(460, topScroll.getHeight());
		// The functional contract, not the bar widget's flag (headless Swing never settles
		// JScrollBar.isVisible reliably): the viewport shows the WHOLE view — no clipped
		// content, nothing to scroll.
		assertTrue("viewport must show the whole top block",
			topScroll.getViewport().getExtentSize().height
				>= topScroll.getViewport().getView().getPreferredSize().height);
	}

	@Test
	public void shortWindowEngagesOuterScrollbar()
	{
		Object[] parts = build();
		JPanel root = (JPanel) parts[0];
		JScrollPane topScroll = (JScrollPane) parts[1];
		JScrollPane rowsScroll = (JScrollPane) parts[2];
		layout(parts, 260);
		// budget 220 < chrome 190 + floor 60 = 250: rows pin to the floor, the top block's
		// preferred (250) exceeds its 220px slot, so the OUTER scrollbar must engage — this is
		// the regime the field screenshot showed failing (clipped content, no scrollbar).
		assertEquals(ROWS_MIN, rowsScroll.getPreferredSize().height);
		assertEquals(220, topScroll.getHeight());
		assertTrue("outer scrollbar must engage when the top block cannot fit",
			topScroll.getVerticalScrollBar().isVisible());
	}
}
