package com.marginallyclever.filters;


import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.marginallyclever.makelangelo.MachineConfiguration;
import com.marginallyclever.makelangelo.MainGUI;
import com.marginallyclever.makelangelo.MultilingualSupport;


public class Filter_GeneratorPulse extends Filter {
  float blockScale = 6.0f;
  int direction = 0;

  public Filter_GeneratorPulse(MainGUI gui, MachineConfiguration mc,
                               MultilingualSupport ms) {
    super(gui, mc, ms);
  }

  @Override
  public String getName() {
    return translator.get("PulseLineName");
  }

  /**
   * Overrides MoveTo() because optimizing for zigzag is different logic than straight lines.
   */
  @Override
  protected void moveTo(Writer out, float x, float y, boolean up) throws IOException {
    if (lastup != up) {
      if (up) liftPen(out);
      else lowerPen(out);
      lastup = up;
    }
    tool.writeMoveTo(out, TX(x), TY(y));
  }

  /**
   * create horizontal lines across the image.  Raise and lower the pen to darken the appropriate areas
   *
   * @param img the image to convert.
   */
  @Override
  public void convert(BufferedImage img) throws IOException {
    final JTextField field_size = new JTextField(Float.toString(blockScale));

    JPanel panel = new JPanel(new GridLayout(0, 1));
    panel.add(new JLabel(translator.get("HilbertCurveSize")));
    panel.add(field_size);

    String[] directions = {"horizontal", "vertical"};
    final JComboBox<String> direction_choices = new JComboBox<>(directions);
    panel.add(direction_choices);

    int result = JOptionPane.showConfirmDialog(null, panel, getName(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (result == JOptionPane.OK_OPTION) {
      blockScale = Float.parseFloat(field_size.getText());
      direction = direction_choices.getSelectedIndex();
      convertNow(img);
    }
  }


  double xStart, yStart;
  double xEnd, yEnd;
  double paperWidth, paperHeight;

  protected int sampleScale(BufferedImage img, double x0, double y0, double x1, double y1) {
    return sample(img,
        (x0 - xStart) / (xEnd - xStart) * (double) image_width,
        (double) image_height - (y1 - yStart) / (yEnd - yStart) * (double) image_height,
        (x1 - xStart) / (xEnd - xStart) * (double) image_width,
        (double) image_height - (y0 - yStart) / (yEnd - yStart) * (double) image_height
    );
  }

  // sample the pixels from x0,y0 (top left) to x1,y1 (bottom right)
  protected int takeImageSampleBlock(BufferedImage img, int x0, int y0, int x1, int y1) {
    // point sampling
    int value = 0;
    int sum = 0;

    if (x0 < 0) x0 = 0;
    if (x1 > image_width - 1) x1 = image_width - 1;
    if (y0 < 0) y0 = 0;
    if (y1 > image_height - 1) y1 = image_height - 1;

    for (int y = y0; y < y1; ++y) {
      for (int x = x0; x < x1; ++x) {
        value += sample1x1(img, x, y);
        ++sum;
      }
    }

    if (sum == 0) return 255;

    return value / sum;
  }


  /**
   * Converts images into zigzags in paper space instead of image space
   *
   * @param img the buffered image to convert
   * @throws IOException couldn't open output file
   */
  private void convertNow(BufferedImage img) throws IOException {
    Filter_BlackAndWhite bw = new Filter_BlackAndWhite(mainGUI, machine, translator, 255);
    img = bw.process(img);

    mainGUI.log("<font color='green'>Converting to gcode and saving " + dest + "</font>\n");
    try (
        final OutputStream fileOutputStream = new FileOutputStream(dest);
        final Writer out = new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8)
    ) {

      imageStart(img, out);

      // set absolute coordinates
      out.write("G00 G90;\n");
      tool.writeChangeTo(out);
      liftPen(out);

//	            convertImageSpace(img, out);
      convertPaperSpace(img, out);

      liftPen(out);
      signName(out);
      moveTo(out, 0, 0, true);
    }
  }


  private void convertPaperSpace(BufferedImage img, Writer out) throws IOException {
    // if the image were projected on the paper, where would the top left corner of the image be in paper space?
    // image(0,0) is (-paperWidth/2,-paperHeight/2)*paperMargin

    paperWidth = machine.getPaperWidth();
    paperHeight = machine.getPaperHeight();

    xStart = -paperWidth / 2.0;
    yStart = xStart * (double) image_height / (double) image_width;

    if (yStart < -(paperHeight / 2.0)) {
      xStart *= (-(paperHeight / 2.0)) / yStart;
      yStart = -(paperHeight / 2.0);
    }

    xStart *= 10.0 * machine.paperMargin;
    yStart *= 10.0 * machine.paperMargin;
    xEnd = -xStart;
    yEnd = -yStart;

    double PULSE_MINIMUM = 0.5;

    // figure out how many lines we're going to have on this image.
    double stepSize = tool.getDiameter() * blockScale;
    double halfStep = stepSize / 2.0;
    double zigZagSpacing = tool.getDiameter();

    // from top to bottom of the image...
    double x, y, z, scale_z, pulse_size, i = 0;
    double n = 1;

    if (direction == 0) {
      // horizontal
      for (y = yStart; y < yEnd; y += stepSize) {
        ++i;

        if ((i % 2) == 0) {
          // every even line move left to right
          //moveToPaper(file,x,y,pen up?)]
          moveToPaper(out, xStart, y + halfStep, true);

          for (x = xStart; x < xEnd; x += zigZagSpacing) {
            // read a block of the image and find the average intensity in this block
            z = sampleScale(img, x - zigZagSpacing, y - halfStep, x + zigZagSpacing, y + halfStep);
            // scale the intensity value
            assert (z >= 0);
            assert (z <= 255.0);
            scale_z = (255.0 - z) / 255.0;
            //scale_z *= scale_z;  // quadratic curve
            pulse_size = halfStep * scale_z;

            moveToPaper(out, x, (y + halfStep + pulse_size * n), pulse_size < PULSE_MINIMUM);
            n = n > 0 ? -1 : 1;
          }
          moveToPaper(out, xEnd, y + halfStep, true);
        } else {
          // every odd line move right to left
          //moveToPaper(file,x,y,pen up?)]
          moveToPaper(out, xEnd, y + halfStep, true);

          for (x = xEnd; x >= xStart; x -= zigZagSpacing) {
            // read a block of the image and find the average intensity in this block
            z = sampleScale(img, x - zigZagSpacing, y - halfStep, x + zigZagSpacing, y + halfStep);
            // scale the intensity value
            scale_z = (255.0 - z) / 255.0;
            //scale_z *= scale_z;  // quadratic curve
            assert (scale_z <= 1.0);
            pulse_size = halfStep * scale_z;
            moveToPaper(out, x, (y + halfStep + pulse_size * n), pulse_size < PULSE_MINIMUM);
            n = n > 0 ? -1 : 1;
          }
          moveToPaper(out, xStart, y + halfStep, true);
        }
      }
    } else {
      // vertical
      for (x = xStart; x < xEnd; x += stepSize) {
        ++i;

        if ((i % 2) == 0) {
          // every even line move top to bottom
          //moveToPaper(file,x,y,pen up?)]
          moveToPaper(out, x + halfStep, yStart, true);

          for (y = yStart; y < yEnd; y += zigZagSpacing) {
            // read a block of the image and find the average intensity in this block
            z = sampleScale(img, x - halfStep, y - zigZagSpacing, x + halfStep, y + zigZagSpacing);
            // scale the intensity value
            scale_z = (255.0f - z) / 255.0f;
            //scale_z *= scale_z;  // quadratic curve
            pulse_size = halfStep * scale_z;
            moveToPaper(out, (x + halfStep + pulse_size * n), y, pulse_size < PULSE_MINIMUM);
            n *= -1;
          }
          moveToPaper(out, x + halfStep, yEnd, true);
        } else {
          // every odd line move bottom to top
          //moveToPaper(file,x,y,pen up?)]
          moveToPaper(out, x + halfStep, yEnd, true);

          for (y = yEnd; y >= yStart; y -= zigZagSpacing) {
            // read a block of the image and find the average intensity in this block
            z = sampleScale(img, x - halfStep, y - zigZagSpacing, x + halfStep, y + zigZagSpacing);
            // scale the intensity value
            scale_z = (255.0f - z) / 255.0f;
            //scale_z *= scale_z;  // quadratic curve
            pulse_size = halfStep * scale_z;
            moveToPaper(out, (x + halfStep + pulse_size * n), y, pulse_size < PULSE_MINIMUM);
            n *= -1;
          }
          moveToPaper(out, x + halfStep, yStart, true);
        }
      }
    }
  }


  /**
   * TODO he detail level on the zigzags cannot be increased beyond the resolution of the bufferedimage because this entire \
   * method was written in image space.  It should be upgraded to paper space to fix the issue.
   *
   * @param img the buffered image to convert
   * @throws IOException couldn't open output file
   */
  private void convertImageSpace(BufferedImage img, Writer out) throws IOException {
    // figure out how many lines we're going to have on this image.
    int steps = (int) Math.ceil(tool.getDiameter() / (2.0f * scale));
    if (steps < 1) steps = 1;

    int blockSize = (int) (steps * blockScale);
    float halfstep = (float) blockSize / 2.0f;
    float zigZagSpacing = steps;

    // from top to bottom of the image...
    int x, y, z, i = 0;
    float n = 1;

    if (direction == 0) {
      // horizontal
      for (y = 0; y < image_height; y += blockSize) {
        ++i;

        if ((i % 2) == 0) {
          // every even line move left to right
          //MoveTo(file,x,y,pen up?)]
          moveTo(out, (float) 0, (float) y + halfstep, true);

          for (x = 0; x < image_width; x += blockSize) {
            // read a block of the image and find the average intensity in this block
            z = takeImageSampleBlock(img, x, (int) (y - halfstep), x + blockSize, (int) (y + halfstep));
            // scale the intensity value
            float scale_z = (255.0f - (float) z) / 255.0f;
            //scale_z *= scale_z;  // quadratic curve
            float pulse_size = halfstep * scale_z;
            if (pulse_size < 0.5f) {
              moveTo(out, x, y + halfstep, true);
              moveTo(out, x + blockSize, y + halfstep, true);
            } else {
              int finalx = x + blockSize;
              if (finalx >= image_width) finalx = image_width - 1;
              // fill the same block in the output image with a heartbeat monitor zigzag.
              // the height of the pulse is relative to the intensity.
              moveTo(out, (float) (x), (float) (y + halfstep), false);
              n *= -1;
              for (int block_x = x; block_x <= finalx; block_x += zigZagSpacing) {
                moveTo(out, (float) (block_x), (float) (y + halfstep + pulse_size * n), false);
                n *= -1;
              }
              moveTo(out, (float) (finalx), (float) (y + halfstep), false);
            }
          }
          moveTo(out, (float) image_width, (float) y + halfstep, true);
        } else {
          // every odd line move right to left
          //MoveTo(file,x,y,pen up?)]
          moveTo(out, (float) image_width, (float) y + halfstep, true);

          for (x = image_width; x >= 0; x -= blockSize) {
            // read a block of the image and find the average intensity in this block
            z = takeImageSampleBlock(img, x - blockSize, (int) (y - halfstep), x, (int) (y + halfstep));
            // scale the intensity value
            float scale_z = (255.0f - (float) z) / 255.0f;
            //scale_z *= scale_z;  // quadratic curve
            float pulse_size = halfstep * scale_z;
            if (pulse_size < 0.5f) {
              moveTo(out, x, y + halfstep, true);
              moveTo(out, x - blockSize, y + halfstep, true);
            } else {
              int finalx = x - blockSize;
              if (finalx < 0) finalx = 0;
              // fill the same block in the output image with a heartbeat monitor zigzag.
              // the height of the pulse is relative to the intensity.
              moveTo(out, (float) (x), (float) (y + halfstep), false);
              n *= -1;
              for (int block_x = x; block_x >= finalx; block_x -= zigZagSpacing) {
                moveTo(out, (float) (block_x), (float) (y + halfstep + pulse_size * n), false);
                n *= -1;
              }
              moveTo(out, (float) (finalx), (float) (y + halfstep), false);
            }
          }
          moveTo(out, (float) 0, (float) y + halfstep, true);
        }
      }
    } else {
      // vertical
      for (x = 0; x < image_width; x += blockSize) {
        ++i;

        if ((i % 2) == 0) {
          // every even line move top to bottom
          //MoveTo(file,x,y,pen up?)]
          moveTo(out, (float) x + halfstep, (float) 0, true);

          for (y = 0; y < image_height; y += blockSize) {
            // read a block of the image and find the average intensity in this block
            //z=takeImageSampleBlock(img,x,(int)(y-halfstep),x+blockSize,(int)(y+halfstep));
            z = takeImageSampleBlock(img, (int) (x - halfstep), y, (int) (x + halfstep), (int) (y + blockSize));
            // scale the intensity value
            float scale_z = (255.0f - (float) z) / 255.0f;
            //scale_z *= scale_z;  // quadratic curve
            float pulse_size = halfstep * scale_z;
            if (pulse_size < 0.5f) {
              moveTo(out, x + halfstep, y, true);
              moveTo(out, x + halfstep, y + blockSize, true);
            } else {
              int finaly = y + blockSize;
              if (finaly >= image_height) finaly = image_height - 1;
              // fill the same block in the output image with a heartbeat monitor zigzag.
              // the height of the pulse is relative to the intensity.
              moveTo(out, (float) (x + halfstep), (float) (y), false);
              n *= -1;
              for (int block_y = y; block_y <= finaly; block_y += zigZagSpacing) {
                moveTo(out, (float) (x + halfstep + pulse_size * n), (float) (block_y), false);
                n *= -1;
              }
              moveTo(out, (float) (x + halfstep), (float) (finaly), false);
            }
          }
          moveTo(out, (float) x + halfstep, (float) image_height, true);
        } else {
          // every odd line move bottom to top
          //MoveTo(file,x,y,pen up?)]
          moveTo(out, (float) x + halfstep, (float) image_height, true);

          for (y = image_height; y >= 0; y -= blockSize) {
            // read a block of the image and find the average intensity in this block
            z = takeImageSampleBlock(img, (int) (x - halfstep), y - blockSize, (int) (x + halfstep), y);
            // scale the intensity value
            float scale_z = (255.0f - (float) z) / 255.0f;
            //scale_z *= scale_z;  // quadratic curve
            float pulse_size = halfstep * scale_z;
            if (pulse_size < 0.5f) {
              moveTo(out, x + halfstep, y, true);
              moveTo(out, x + halfstep, y - blockSize, true);
            } else {
              int finaly = y - blockSize;
              if (finaly < 0) finaly = 0;
              // fill the same block in the output image with a heartbeat monitor zigzag.
              // the height of the pulse is relative to the intensity.
              moveTo(out, (float) (x + halfstep), (float) (y), false);
              n *= -1;
              for (int block_y = y; block_y >= finaly; block_y -= zigZagSpacing) {
                moveTo(out, (float) (x + halfstep + pulse_size * n), (float) (block_y), false);
                n *= -1;
              }
              moveTo(out, (float) (x + halfstep), (float) (finaly), false);
            }
          }
          moveTo(out, (float) x + halfstep, (float) 0, true);
        }
      }
    }
  }
}


/**
 * This file is part of DrawbotGUI.
 * <p>
 * DrawbotGUI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * DrawbotGUI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with DrawbotGUI.  If not, see <http://www.gnu.org/licenses/>.
 */
