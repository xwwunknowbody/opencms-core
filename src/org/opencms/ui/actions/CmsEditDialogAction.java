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

package org.opencms.ui.actions;

import org.opencms.file.CmsObject;
import org.opencms.file.CmsResource;
import org.opencms.main.CmsLog;
import org.opencms.main.OpenCms;
import org.opencms.ui.I_CmsDialogContext;
import org.opencms.ui.apps.CmsAppView;
import org.opencms.ui.apps.CmsAppView.CacheStatus;
import org.opencms.ui.apps.CmsAppWorkplaceUi;
import org.opencms.ui.apps.CmsEditor;
import org.opencms.ui.contextmenu.CmsStandardVisibilityCheck;
import org.opencms.ui.contextmenu.I_CmsHasMenuItemVisibility;
import org.opencms.workplace.explorer.Messages;
import org.opencms.workplace.explorer.menu.CmsMenuItemVisibilityMode;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

import org.apache.commons.logging.Log;

import com.vaadin.navigator.View;
import com.vaadin.ui.UI;

/**
 * The edit dialog action.<p>
 */
public class CmsEditDialogAction extends A_CmsWorkplaceAction {

    /** The action id. */
    public static final String ACTION_ID = "edit";

    /** The action visibility. */
    public static final I_CmsHasMenuItemVisibility VISIBILITY = CmsStandardVisibilityCheck.EDIT;

    /** Log instance for this class. */
    private static final Log LOG = CmsLog.getLog(CmsEditDialogAction.class);

    /**
     * @see org.opencms.ui.actions.I_CmsWorkplaceAction#executeAction(org.opencms.ui.I_CmsDialogContext)
     */
    public void executeAction(I_CmsDialogContext context) {

        String backLink;
        try {
            String currentLocation = UI.getCurrent().getPage().getLocation().toString();
            backLink = URLEncoder.encode(currentLocation, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            LOG.error(e.getLocalizedMessage(), e);
            backLink = UI.getCurrent().getPage().getLocation().toString();
        }
        View view = CmsAppWorkplaceUi.get().getCurrentView();
        if (view instanceof CmsAppView) {
            ((CmsAppView)view).setCacheStatus(CacheStatus.cacheOnce);
        }
        CmsAppWorkplaceUi.get().showApp(
            OpenCms.getWorkplaceAppManager().getAppConfiguration("editor"),
            CmsEditor.getEditState(context.getResources().get(0).getStructureId(), false, backLink));
    }

    /**
     * @see org.opencms.ui.actions.I_CmsWorkplaceAction#getId()
     */
    public String getId() {

        return ACTION_ID;
    }

    /**
     * @see org.opencms.ui.actions.I_CmsWorkplaceAction#getTitle()
     */
    public String getTitle() {

        return getWorkplaceMessage(Messages.GUI_EXPLORER_CONTEXT_EDIT_0);
    }

    /**
     * @see org.opencms.ui.contextmenu.I_CmsHasMenuItemVisibility#getVisibility(org.opencms.file.CmsObject, java.util.List)
     */
    public CmsMenuItemVisibilityMode getVisibility(CmsObject cms, List<CmsResource> resources) {

        return VISIBILITY.getVisibility(cms, resources);
    }
}
