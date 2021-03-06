/*
 * This library is part of OpenCms -
 * the Open Source Content Management System
 *
 * Copyright (c) Alkacon Software GmbH (http://www.alkacon.com)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * For further information about Alkacon Software, please see the
 * company website: http://www.alkacon.com
 *
 * For further information about OpenCms, please see the
 * project website: http://www.opencms.org
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.opencms.ui.components.fileselect;

import org.opencms.file.CmsResource;
import org.opencms.file.CmsResourceFilter;
import org.opencms.main.CmsException;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.ui.A_CmsUI;
import org.opencms.ui.CmsVaadinUtils;
import org.opencms.ui.components.CmsBasicDialog;
import org.opencms.ui.components.CmsErrorDialog;
import org.opencms.ui.components.OpenCmsTheme;
import org.opencms.workplace.CmsWorkplace;

import org.apache.commons.logging.Log;

import com.vaadin.server.ExternalResource;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.ui.CustomField;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.TextField;
import com.vaadin.ui.Window;
import com.vaadin.ui.themes.ValoTheme;

/**
 * Abstract file select field. Used by {@link org.opencms.ui.components.fileselect.CmsResourceSelectField} and {@link org.opencms.ui.components.fileselect.CmsPathSelectField}.<p>
 *
 * @param <T> the value type
 */
public abstract class A_CmsFileSelectField<T> extends CustomField<T> {

    /** Logger instance for this class. */
    private static final Log LOG = CmsLog.getLog(A_CmsFileSelectField.class);

    /** The serial version id. */
    private static final long serialVersionUID = 1L;

    /** The filter used for reading resources. */
    protected CmsResourceFilter m_filter;

    /** The text field containing the selected path. */
    protected TextField m_textField;

    /** The file select dialog caption. */
    private String m_fileSelectCaption;

    /**
     * Creates a new instance.<p>
     */
    public A_CmsFileSelectField() {
        m_textField = new TextField();
        m_textField.setWidth("100%");
        m_filter = CmsResourceFilter.ONLY_VISIBLE_NO_DELETED;
    }

    /**
     * Sets the caption of the file select dialog.<p>
     *
     * @param caption the caption
     */
    public void setFileSelectCaption(String caption) {

        m_fileSelectCaption = caption;
    }

    /**
     * Sets the filter to use for reading resources.<p>
     *
     * @param filter the new filter
     */
    public void setResourceFilter(CmsResourceFilter filter) {

        m_filter = filter;

    }

    /**
     * @see com.vaadin.ui.CustomField#initContent()
     */
    @Override
    protected HorizontalLayout initContent() {

        HorizontalLayout layout = new HorizontalLayout();
        layout.setWidth("100%");
        layout.setSpacing(true);
        layout.addComponent(m_textField);
        Button fileSelectButton = new Button("");
        fileSelectButton.addStyleName(OpenCmsTheme.BUTTON_UNPADDED);
        fileSelectButton.addStyleName(ValoTheme.BUTTON_LINK);
        ExternalResource folderRes = new ExternalResource(
            CmsWorkplace.getResourceUri(
                CmsWorkplace.RES_PATH_FILETYPES
                    + OpenCms.getWorkplaceManager().getExplorerTypeSetting("folder").getBigIconIfAvailable()));
        fileSelectButton.setIcon(folderRes);

        layout.addComponent(fileSelectButton);
        layout.setExpandRatio(m_textField, 1f);

        fileSelectButton.addClickListener(new ClickListener() {

            /** Serial version id. */
            private static final long serialVersionUID = 1L;

            public void buttonClick(ClickEvent event) {

                openFileSelector();
            }
        });
        return layout;
    }

    /**
     * Opens the file selector dialog.<p>
     */
    protected void openFileSelector() {

        try {
            CmsResourceSelectDialog fileSelect = new CmsResourceSelectDialog(m_filter);
            final Window window = CmsBasicDialog.prepareWindow();
            window.setCaption(
                m_fileSelectCaption != null
                ? m_fileSelectCaption
                : CmsVaadinUtils.getMessageText(org.opencms.ui.components.Messages.GUI_FILE_SELECT_CAPTION_0));
            window.setContent(fileSelect);
            fileSelect.addSelectionHandler(new I_CmsSelectionHandler<CmsResource>() {

                public void onSelection(CmsResource selected) {

                    setResourceValue(selected);
                    window.close();
                }
            });

            A_CmsUI.get().addWindow(window);
        } catch (CmsException e) {
            LOG.error(e.getLocalizedMessage(), e);
            CmsErrorDialog.showErrorDialog(e);
        }
    }

    /**
     * Sets the field value.<p>
     *
     * @param resource the resource
     */
    protected abstract void setResourceValue(CmsResource resource);
}
