/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Footer Component - Yelp Style
 * 
 * شريط التنقل السفلي - نبض الضاحية وقدسيا
 */

'use client';

import React from 'react';
import Link from 'next/link';
import { Facebook, Instagram, Twitter, Globe, ChevronDown } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';

export default function Footer() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';

  const content = {
    ar: {
      about: {
        title: 'عن نبض',
        links: [
          { name: 'من نحن', path: '/about' },
          { name: 'اتصل بنا', path: '/contact' },
          { name: 'وظائف', path: '/careers' },
          { name: 'الأخبار', path: '/news' },
        ]
      },
      discover: {
        title: 'اكتشف',
        links: [
          { name: 'مطاعم ومقاهي', path: '/restaurants' },
          { name: 'أطباء وصيدليات', path: '/doctors' },
          { name: 'خدمات', path: '/services' },
          { name: 'عقارات', path: '/real-estate' },
          { name: 'وظائف شاغرة', path: '/jobs' },
        ]
      },
      business: {
        title: 'للأعمال',
        links: [
          { name: 'أضف نشاطك', path: '/add-business' },
          { name: 'إعلانات', path: '/advertise' },
          { name: 'دليل الأعمال', path: '/business' },
        ]
      },
      directory: {
        title: 'الدليل',
        links: [
          { name: 'صيدليات مناوبة', path: '/pharmacies' },
          { name: 'أرقام الطوارئ', path: '/emergency' },
          { name: 'حرفيين', path: '/craftsmen' },
          { name: 'أسواق', path: '/markets' },
        ]
      },
      cities: {
        title: 'المناطق',
        links: [
          { name: 'قدسيا', path: '?location=qudsaya' },
          { name: 'الضاحية', path: '?location=dahia' },
          { name: 'الديماس', path: '?location=dimas' },
        ]
      },
      rights: 'جميع الحقوق محفوظة © نبض الضاحية وقدسيا',
      privacy: 'سياسة الخصوصية',
      terms: 'شروط الاستخدام',
      accessibility: 'إمكانية الوصول',
    },
    en: {
      about: {
        title: 'About',
        links: [
          { name: 'About Nabd', path: '/about' },
          { name: 'Contact', path: '/contact' },
          { name: 'Careers', path: '/careers' },
          { name: 'News', path: '/news' },
        ]
      },
      discover: {
        title: 'Discover',
        links: [
          { name: 'Restaurants & Cafes', path: '/restaurants' },
          { name: 'Doctors & Pharmacies', path: '/doctors' },
          { name: 'Services', path: '/services' },
          { name: 'Real Estate', path: '/real-estate' },
          { name: 'Jobs', path: '/jobs' },
        ]
      },
      business: {
        title: 'For Business',
        links: [
          { name: 'Add Your Business', path: '/add-business' },
          { name: 'Advertise', path: '/advertise' },
          { name: 'Business Directory', path: '/business' },
        ]
      },
      directory: {
        title: 'Directory',
        links: [
          { name: 'On-Duty Pharmacies', path: '/pharmacies' },
          { name: 'Emergency Numbers', path: '/emergency' },
          { name: 'Craftsmen', path: '/craftsmen' },
          { name: 'Markets', path: '/markets' },
        ]
      },
      cities: {
        title: 'Cities',
        links: [
          { name: 'Qudsaya', path: '?location=qudsaya' },
          { name: 'Dahia', path: '?location=dahia' },
          { name: 'Dimas', path: '?location=dimas' },
        ]
      },
      rights: '© Nabd Dahia & Qudsaya. All rights reserved.',
      privacy: 'Privacy Policy',
      terms: 'Terms of Service',
      accessibility: 'Accessibility',
    }
  };

  const t = isArabic ? content.ar : content.en;

  return (
    <footer className="bg-gray-100 border-t border-gray-200">
      {/* Main Footer Content */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
        {/* Logo & Social Icons */}
        <div className="flex items-center justify-between mb-8 pb-6 border-b border-gray-200">
          <Link href="/" className="flex items-center gap-2">
            <span className="text-2xl font-bold text-red-500">
              {isArabic ? 'نبض' : 'Nabd'}
            </span>
          </Link>
          
          <div className="flex items-center gap-3">
            <a href="#" className="w-10 h-10 rounded-full bg-gray-200 flex items-center justify-center hover:bg-red-500 hover:text-white transition-all text-gray-600">
              <Facebook className="w-5 h-5" />
            </a>
            <a href="#" className="w-10 h-10 rounded-full bg-gray-200 flex items-center justify-center hover:bg-red-500 hover:text-white transition-all text-gray-600">
              <Instagram className="w-5 h-5" />
            </a>
            <a href="#" className="w-10 h-10 rounded-full bg-gray-200 flex items-center justify-center hover:bg-red-500 hover:text-white transition-all text-gray-600">
              <Twitter className="w-5 h-5" />
            </a>
          </div>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-5 gap-8">
          {/* About */}
          <div>
            <h3 className="text-sm font-bold text-gray-900 mb-4">{t.about.title}</h3>
            <ul className="space-y-2.5">
              {t.about.links.map((link) => (
                <li key={link.path}>
                  <Link href={link.path} className="text-sm text-gray-600 hover:text-red-500 transition-colors">
                    {link.name}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Discover */}
          <div>
            <h3 className="text-sm font-bold text-gray-900 mb-4">{t.discover.title}</h3>
            <ul className="space-y-2.5">
              {t.discover.links.map((link) => (
                <li key={link.path}>
                  <Link href={link.path} className="text-sm text-gray-600 hover:text-red-500 transition-colors">
                    {link.name}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* For Business */}
          <div>
            <h3 className="text-sm font-bold text-gray-900 mb-4">{t.business.title}</h3>
            <ul className="space-y-2.5">
              {t.business.links.map((link) => (
                <li key={link.path}>
                  <Link href={link.path} className="text-sm text-gray-600 hover:text-red-500 transition-colors">
                    {link.name}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Directory */}
          <div>
            <h3 className="text-sm font-bold text-gray-900 mb-4">{t.directory.title}</h3>
            <ul className="space-y-2.5">
              {t.directory.links.map((link) => (
                <li key={link.path}>
                  <Link href={link.path} className="text-sm text-gray-600 hover:text-red-500 transition-colors">
                    {link.name}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Cities */}
          <div>
            <h3 className="text-sm font-bold text-gray-900 mb-4">{t.cities.title}</h3>
            <ul className="space-y-2.5">
              {t.cities.links.map((link) => (
                <li key={link.path}>
                  <Link href={link.path} className="text-sm text-gray-600 hover:text-red-500 transition-colors">
                    {link.name}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        </div>

        {/* Language & Country Selectors */}
        <div className="mt-10 pt-6 border-t border-gray-200 flex flex-wrap items-center gap-4">
          <button className="flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900">
            <Globe className="w-4 h-4" />
            <span>{isArabic ? 'العربية' : 'English'}</span>
            <ChevronDown className="w-3 h-3" />
          </button>
          
          <span className="text-gray-300">|</span>
          
          <button className="flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900">
            <span>{isArabic ? 'سوريا' : 'Syria'}</span>
            <ChevronDown className="w-3 h-3" />
          </button>
        </div>
      </div>

      {/* Bottom Bar */}
      <div className="bg-gray-200 border-t border-gray-300">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex flex-col sm:flex-row justify-between items-center gap-4">
            {/* Copyright */}
            <p className="text-xs text-gray-500">{t.rights}</p>

            {/* Links */}
            <div className="flex items-center gap-4 text-xs">
              <Link href="/privacy" className="text-gray-600 hover:text-gray-900 transition-colors">
                {t.privacy}
              </Link>
              <Link href="/terms" className="text-gray-600 hover:text-gray-900 transition-colors">
                {t.terms}
              </Link>
              <Link href="/accessibility" className="text-gray-600 hover:text-gray-900 transition-colors">
                {t.accessibility}
              </Link>
            </div>

            {/* Social Icons */}
            <div className="flex items-center gap-3">
              <a href="#" className="w-8 h-8 rounded-full bg-gray-300 flex items-center justify-center hover:bg-red-500 hover:text-white transition-all text-gray-600">
                <Facebook className="w-4 h-4" />
              </a>
              <a href="#" className="w-8 h-8 rounded-full bg-gray-300 flex items-center justify-center hover:bg-red-500 hover:text-white transition-all text-gray-600">
                <Instagram className="w-4 h-4" />
              </a>
              <a href="#" className="w-8 h-8 rounded-full bg-gray-300 flex items-center justify-center hover:bg-red-500 hover:text-white transition-all text-gray-600">
                <Twitter className="w-4 h-4" />
              </a>
            </div>
          </div>
        </div>
      </div>
    </footer>
  );
}
