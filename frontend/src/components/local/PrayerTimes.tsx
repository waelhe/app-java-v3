'use client';

import React, { useState, useEffect, createElement } from 'react';
import { Moon, Sun, Sunrise, Sunset, CloudMoon, Clock, MapPin, ChevronDown, ChevronUp, Bell } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';
import RegionSelector from './RegionSelector';
import { useRegion, Region } from '@/contexts/RegionContext';

interface PrayerTime {
  id: string;
  name: string;
  nameEn: string;
  time: string;
  icon: React.ElementType;
  color: string;
  bgColor: string;
}

interface PrayerData {
  prayers: PrayerTime[];
  location: string;
  locationEn: string;
}

const qudsayaCenterData: PrayerData = {
  prayers: [
    { id: 'fajr', name: 'الفجر', nameEn: 'Fajr', time: '05:10', icon: Moon, color: 'text-indigo-600', bgColor: 'bg-indigo-100' },
    { id: 'sunrise', name: 'الشروق', nameEn: 'Sunrise', time: '06:35', icon: Sunrise, color: 'text-orange-500', bgColor: 'bg-orange-100' },
    { id: 'dhuhr', name: 'الظهر', nameEn: 'Dhuhr', time: '12:40', icon: Sun, color: 'text-yellow-500', bgColor: 'bg-yellow-100' },
    { id: 'asr', name: 'العصر', nameEn: 'Asr', time: '16:10', icon: CloudMoon, color: 'text-amber-500', bgColor: 'bg-amber-100' },
    { id: 'maghrib', name: 'المغرب', nameEn: 'Maghrib', time: '18:50', icon: Sunset, color: 'text-rose-500', bgColor: 'bg-rose-100' },
    { id: 'isha', name: 'العشاء', nameEn: 'Isha', time: '20:15', icon: Moon, color: 'text-purple-500', bgColor: 'bg-purple-100' }
  ],
  location: 'قدسيا، دمشق',
  locationEn: 'Qudsaya, Damascus'
};

const qudsayaDahiaData: PrayerData = {
  prayers: [
    { id: 'fajr', name: 'الفجر', nameEn: 'Fajr', time: '05:12', icon: Moon, color: 'text-indigo-600', bgColor: 'bg-indigo-100' },
    { id: 'sunrise', name: 'الشروق', nameEn: 'Sunrise', time: '06:38', icon: Sunrise, color: 'text-orange-500', bgColor: 'bg-orange-100' },
    { id: 'dhuhr', name: 'الظهر', nameEn: 'Dhuhr', time: '12:45', icon: Sun, color: 'text-yellow-500', bgColor: 'bg-yellow-100' },
    { id: 'asr', name: 'العصر', nameEn: 'Asr', time: '16:15', icon: CloudMoon, color: 'text-amber-500', bgColor: 'bg-amber-100' },
    { id: 'maghrib', name: 'المغرب', nameEn: 'Maghrib', time: '18:52', icon: Sunset, color: 'text-rose-500', bgColor: 'bg-rose-100' },
    { id: 'isha', name: 'العشاء', nameEn: 'Isha', time: '20:18', icon: Moon, color: 'text-purple-500', bgColor: 'bg-purple-100' }
  ],
  location: 'ضاحية قدسيا، دمشق',
  locationEn: 'Qudsaya Dahia, Damascus'
};

const dataByRegion: Record<Region, PrayerData> = {
  'qudsaya-center': qudsayaCenterData,
  'qudsaya-dahia': qudsayaDahiaData
};

export default function PrayerTimes() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region, regionName } = useRegion();
  const prayerData = dataByRegion[region];
  const prayers = prayerData.prayers;
  
  const [prayerInfo, setPrayerInfo] = useState({ id: 'fajr', countdown: '--:--' });
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    const updatePrayerInfo = () => {
      const now = new Date();
      const currentMinutes = now.getHours() * 60 + now.getMinutes();

      for (const prayer of prayers) {
        const [hours, minutes] = prayer.time.split(':').map(Number);
        const prayerMinutes = hours * 60 + minutes;

        if (prayerMinutes > currentMinutes) {
          const diff = prayerMinutes - currentMinutes;
          const h = Math.floor(diff / 60);
          const m = diff % 60;
          setPrayerInfo({
            id: prayer.id,
            countdown: `${h}:${m.toString().padStart(2, '0')}`
          });
          return;
        }
      }
      setPrayerInfo({ id: 'fajr', countdown: '--:--' });
    };

    updatePrayerInfo();
    const interval = setInterval(updatePrayerInfo, 60000);
    return () => clearInterval(interval);
  }, [prayers]);

  const nextPrayerId = prayerInfo.id;
  const countdown = prayerInfo.countdown;
  const nextPrayer = prayers.find(p => p.id === nextPrayerId);

  return (
    <section className="py-3 bg-gradient-to-b from-purple-50 to-white">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Header */}
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <div className="p-2 bg-purple-600 rounded-xl shadow-lg shadow-purple-200">
              <Bell className="w-4 h-4 text-white" />
            </div>
            <div>
              <h2 className="text-base font-black text-gray-900">
                {isArabic ? 'أوقات الصلاة' : 'Prayer Times'}
              </h2>
              <div className="flex items-center gap-1 text-xs text-gray-500">
                <MapPin className="w-3 h-3 text-purple-500" />
                <span>{isArabic ? prayerData.location : prayerData.locationEn}</span>
              </div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <div className="text-right hidden sm:block">
              <p className="text-xs text-gray-500">{isArabic ? 'اليوم' : 'Today'}</p>
              <p className="text-sm font-bold text-gray-700">
                {new Date().toLocaleDateString(isArabic ? 'ar-SY' : 'en-US', { weekday: 'long' })}
              </p>
            </div>
            <RegionSelector />
          </div>
        </div>

        {/* Next Prayer Card - Compact */}
        {nextPrayer && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            className="mb-2 p-3 bg-gradient-to-r from-purple-600 to-purple-700 rounded-xl text-white shadow-lg"
          >
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="p-2 bg-white/20 rounded-xl backdrop-blur-sm">
                  {createElement(nextPrayer.icon, { className: 'w-5 h-5 text-white' })}
                </div>
                <div>
                  <p className="text-purple-200 text-xs font-medium">
                    {isArabic ? 'الصلاة القادمة' : 'Next Prayer'}
                  </p>
                  <h3 className="text-lg font-black">
                    {isArabic ? nextPrayer.name : nextPrayer.nameEn}
                  </h3>
                </div>
              </div>
              <div className="text-left flex items-center gap-4">
                <div>
                  <p className="text-2xl font-black">
                    {nextPrayer.time}
                  </p>
                  <div className="flex items-center gap-1 justify-end">
                    <Clock className="w-3 h-3 text-purple-200" />
                    <span className="text-xs text-purple-200">
                      {isArabic ? `متبقي ${countdown}` : `${countdown} left`}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </motion.div>
        )}

        {/* All Prayers Grid */}
        <AnimatePresence>
          {showAll && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              exit={{ opacity: 0, height: 0 }}
              className="grid grid-cols-3 md:grid-cols-6 gap-2 mt-2"
            >
              {prayers.map((prayer) => {
                const Icon = prayer.icon;
                const isNext = prayer.id === nextPrayerId;

                return (
                  <div
                    key={prayer.id}
                    className={`p-2 rounded-xl text-center transition-all ${
                      isNext 
                        ? 'bg-purple-100 border-2 border-purple-300 shadow-md' 
                        : 'bg-white border border-gray-100 hover:border-gray-200'
                    }`}
                  >
                    <div className={`p-1.5 rounded-lg ${prayer.bgColor} w-fit mx-auto mb-1`}>
                      <Icon className={`w-4 h-4 ${isNext ? 'text-purple-600' : prayer.color}`} />
                    </div>
                    <h4 className="font-bold text-gray-900 text-xs mb-0.5">
                      {isArabic ? prayer.name : prayer.nameEn}
                    </h4>
                    <span className={`text-sm font-black ${isNext ? 'text-purple-600' : 'text-gray-800'}`}>
                      {prayer.time}
                    </span>
                  </div>
                );
              })}
            </motion.div>
          )}
        </AnimatePresence>

        {/* Toggle Button */}
        <button
          onClick={() => setShowAll(!showAll)}
          className="w-full mt-2 flex items-center justify-center gap-1 text-purple-600 text-xs font-bold py-1.5 bg-purple-50 hover:bg-purple-100 rounded-lg transition-colors"
        >
          {showAll 
            ? (isArabic ? 'إخفاء الأوقات' : 'Hide Times')
            : (isArabic ? 'عرض جميع الأوقات' : 'Show All Times')
          }
          {showAll 
            ? <ChevronUp className="w-3 h-3" />
            : <ChevronDown className="w-3 h-3" />
          }
        </button>
      </div>
    </section>
  );
}
